package com.neowit.apex.actions.tooling

import com.neowit.apex.{MetadataType, Session}
import java.io.File
import com.neowit.apex.actions._
import com.sforce.soap.tooling._
import com.neowit.utils.ResponseWriter.Message
import com.neowit.utils.{ZipUtils, FileUtils, ZuluTime, ResponseWriter}
import scala.concurrent._
import com.neowit.utils.ResponseWriter.MessageDetail
import com.sforce.ws.bind.XmlObject

import scala.util.{Success, Failure, Try}

/**
 * SaveModified tries to leverage ToolingApi and works only in Dev Orgs and Sandboxes
 *
 * Extra command line params:
 * --ignoreConflicts=true|false (defaults to false) - if true then skip ListConflicting check
 * --checkOnly=true|false (defaults to false) - if true then do a dry-run without modifying SFDC
 * --testsToRun=* OR "comma separated list of class.method names",
 * e.g. "ControllerTest.myTest1, ControllerTest.myTest2, HandlerTest1.someTest, Test3.anotherTest1"
 *
 * class/method can be specified in two forms
 * - ClassName[.methodName] -  means specific method of specific class
 * - ClassName -  means *all* test methodsToKeep of specific class
 *
 * if --testsToRun=* (star) then run all tests in all classes (containing testMethod or @isTest ) in
 * the *current* deployment package
 * --reportCoverage=true|false (defaults to false) - if true then generate code coverage file
 *
 */
class SaveModified extends DeployModified {
    val CONTAINER_PREFIX = "tooling-force.com"

    def deleteMetadataContainer(session: Session) {
        getExistingMetadataContainer(session)  match {
            case Some(container) =>
                try {
                    session.removeData("MetadataContainer")
                    session.deleteTooling(container.getId)
                    logger.debug("Deleted MetadataContainer; Id=" + container.getId)
                } catch {
                    case ex:Throwable => //do not really care why delete failed
                        logger.debug("Could not delete MetadataContainer. " + ex.getMessage)
                }
                session.storeSessionData()
            case None => //nothing to delete
        }
    }

    def getExistingMetadataContainer(session: Session): Option[MetadataContainer] = {
        session.getData("MetadataContainer")  match {
            case data: Map[String, _] if "" != data.getOrElse("Id", "").asInstanceOf[String] =>
                val containerId = data("Id").asInstanceOf[String]
                //logger.debug("Re-use Existing MetadataContainer; Id=" + containerId)
                val container = new MetadataContainer()
                container.setId(containerId)
                container.setName(data.getOrElse("Name", "").asInstanceOf[String])
                Some(container)
            case _ => None
        }
    }

    def withMetadataContainer(session: Session)(codeBlock: (MetadataContainer) => Any) = {

        try {
            codeBlock(getMetadataContainer(session))
        } catch {
            case ex:Throwable =>
                logger.debug(ex)
                //delete container
                deleteMetadataContainer(session)
                //and try again
                codeBlock(getMetadataContainer(session))
        } finally {
            deleteMetadataContainer(session)
        }
    }
    def getMetadataContainer(session: Session): MetadataContainer = {
        val container = getExistingMetadataContainer(session)  match {
            case Some(_container) => _container
            case None =>
                val newContainer = new MetadataContainer()
                newContainer.setName(CONTAINER_PREFIX + session.getUserId.drop(3))//reduce length to fit in 32 characters
            val containerSaveResults: Array[SaveResult] = session.createTooling(Array(newContainer))
                if (containerSaveResults.head.isSuccess) {
                    newContainer.setId(containerSaveResults.head.getId)
                    session.setData("MetadataContainer", Map("Name" -> newContainer.getName, "Id" -> newContainer.getId))
                    session.storeSessionData()
                    logger.debug("Created new MetadataContainer; Id=" + newContainer.getName + " - Id=" + newContainer.getId)
                } else {
                    val msg = "Failed to create Metadata Container. " + containerSaveResults.head.getErrors.head.getMessage
                    throw new IllegalStateException(msg)
                }
                newContainer
        }
        container
    }

    private val ONE_SECOND = 1000

    /**
     * @return - true if deployment is successful
     */
    override def deploy(files: List[File], updateSessionDataOnSuccess: Boolean): Boolean = {
        logger.debug("Entered SaveModified.deploy()")
        if (!ToolingUtils.canUseTooling(session, files)) {
            //can not use tooling, fall back to metadata version - DeployModified
            super.deploy(files, updateSessionDataOnSuccess)
        } else {
            val hasAuraFiles = files.exists(AuraMember.isSupportedType(_))
            if (!hasAuraFiles) {
                //split file by MetadataContainer vs ContainerLess
                val filesByContainerType = files.groupBy(f => if (ApexMember.isSupportedTypeWithMetadataContainer(f, session)) "MetadataContainer" else "ContainerLess")
                //files to be saved in MetadataContainer
                val res1 = filesByContainerType.get("MetadataContainer") match {
                  case Some(_files) =>
                      deployWithMetadataContaner(_files, updateSessionDataOnSuccess)
                  case None => true
                }
                //files to be saved standalone
                val res2 = filesByContainerType.get("ContainerLess") match {
                    case Some(_files) =>
                        saveContainerLessFiles(_files, updateSessionDataOnSuccess)
                    case None => true
                }
                res1 && res2
            } else {
                //aura
                deployAura(files, updateSessionDataOnSuccess)
            }
        }

    }

    /**
     * some file types supported by Tooling API do not require MetadataContainer, so we save them in parallel
     * in separate requests
     * @param files - files to save with Tooling API
     * @param updateSessionDataOnSuccess - if session data needs to be updated at the end of successful save
     * @return - true if all files have been saved successfully
     */
    private def saveContainerLessFiles(files: List[File], updateSessionDataOnSuccess: Boolean): Boolean = {
        val successByExtension = files.groupBy(FileUtils.getExtension(_)).par.mapValues{files =>
            saveFilesOfSingleXmlType(files, fileToToolingInstance,
                (files: List[File]) => updateFileModificationData(files), updateSessionDataOnSuccess)
        }
        val hasFailure = successByExtension.exists{case (extension, res) => !res}
        !hasFailure
    }

    /**
     *
     * @param files - all files must be aura files
     * @param updateSessionDataOnSuccess - if true then update session if deployment is successful
     * @return
     */
    private def saveFilesOfSingleXmlType(files: List[File], sobjectInstanceCreator: (File) => SObject,
                                         sessionDataUpdater: (List[File]) => Unit, updateSessionDataOnSuccess: Boolean): Boolean = {

        val sObjects = files.map(sobjectInstanceCreator(_))
        val saveResults = session.updateTooling(sObjects.toArray)

        val errorBuilderByFileIndex = Map.newBuilder[Int, com.sforce.soap.tooling.Error]

        var index = 0
        val fileArray = files.toArray

        //prepare list of files to load LastModifiedDate from SFDC
        val successfulFilesBuilder = List.newBuilder[File]
        for (saveResult <- saveResults) {
            if (saveResult.isSuccess) {
                successfulFilesBuilder += fileArray(index)
            } else {
                errorBuilderByFileIndex += index -> saveResult.getErrors.head
            }
            index += 1
        }

        val errorByFileIndex = errorBuilderByFileIndex.result()
        val successfulFiles = successfulFilesBuilder.result()

        if (updateSessionDataOnSuccess && successfulFiles.nonEmpty) {
            //updateFileModificationData(successfulFiles)
            sessionDataUpdater(successfulFiles)
            session.storeSessionData()

            if (errorByFileIndex.isEmpty) {
                config.responseWriter.println("RESULT=SUCCESS")
                config.responseWriter.println("FILE_COUNT=" + files.size)
                if (!config.isCheckOnly) {
                    config.responseWriter.startSection("SAVED FILES")
                    files.foreach(f => config.responseWriter.println(f.getName))
                    config.responseWriter.endSection("SAVED FILES")
                }
            }

        }

        //now process errors
        if (errorByFileIndex.nonEmpty) {
            logger.debug("Request failed")
            responseWriter.println("RESULT=FAILURE")
            config.responseWriter.startSection("ERROR LIST")
            val problemType = "ERROR"
            val componentFailureMessage = new Message(ResponseWriter.WARN, "Compiler errors")
            responseWriter.println(componentFailureMessage)

            for (index <- errorByFileIndex.keys) {
                val file = fileArray(index)
                val filePath = session.getRelativePath(file)
                val error = errorByFileIndex(index)
                var problem = error.getMessage
                if (StatusCode.INVALID_ID_FIELD == error.getStatusCode) {
                    problem = s"Stored Id of ${file.getName} is no longer valid. " +
                        "You may want to call 'refresh' to make sure there are no aura files deleted and re-created with new Ids. " +
                        "Alternatively (to force deployment) - use 'deploy' instead of 'save' to force using Metadata API. " +
                        s"Original error: $problem"
                }
                val statusCode = error.getStatusCode.toString
                val fields = error.getFields
                //display errors both as messages and as ERROR: lines
                responseWriter.println("ERROR", Map("type" -> problemType, "filePath" -> filePath, "text" -> problem, "fields" -> fields.mkString(",")))
                responseWriter.println(new MessageDetail(componentFailureMessage, Map("type" -> problemType, "filePath" -> filePath, "text" -> problem, "code" -> statusCode, "fields" -> fields.mkString(","))))
            }
            false
        } else {
            true
        }
    }

    /**
     * this method assumes that we know Ids of all files (ids should be already saved in session)
     */
    def updateFileModificationData(files: List[File], providedXmlType: Option[String] = None): Unit = {
        val modificationDataByFile = getFilesModificationData(files)
        for (f <- modificationDataByFile.keys) {
            val xmlType = providedXmlType.getOrElse(DescribeMetadata.getXmlNameBySuffix(session, FileUtils.getExtension(f)).getOrElse(""))
            modificationDataByFile.get(f) match {
                case Some(data) =>
                    val key = session.getKeyByFile(f)
                    val recordId = data("Id").toString
                    val lastModifiedDate = ZuluTime.deserialize(data("Remote-LastModifiedDateStr").toString)
                    val newData = MetadataType.getValueMap(config, f, xmlType, Some(recordId), lastModifiedDate, fileMeta = None)
                    val oldData = session.getData(key)
                    session.setData(key, oldData ++ newData)
                case None =>
            }
        }
        //session.storeSessionData()

    }

    private def fileToToolingInstance(file: File): com.sforce.soap.tooling.SObject = {
        val sobject = FileUtils.getExtension(file).toLowerCase match {
            case "resource" =>
                val resource = new StaticResource
                resource.setBody(ZipUtils.getBytes(file))
                resource
            case x => throw new RuntimeException("saveModified: Unsupported type " + x)
        }
        //set object Id if available
        session.getData(file).get("Id") match {
            case Some(objectId:String) =>
                sobject.setId(objectId)
            case _ =>
        }
        sobject
    }


    /**
     *
     * @param files - all files must be aura files
     * @param updateSessionDataOnSuccess - if true then update session if deployment is successful
     * @return
     */
    private def deployAura(files: List[File], updateSessionDataOnSuccess: Boolean): Boolean = {
        saveFilesOfSingleXmlType(files,
                                (file: File) => AuraMember.getInstanceUpdate(file, session),
                                (files: List[File]) => updateFileModificationData(files, Some(AuraMember.XML_TYPE)),
                                updateSessionDataOnSuccess)
    }

    private def deployWithMetadataContaner(files: List[File], updateSessionDataOnSuccess: Boolean): Boolean = {
        logger.debug("Deploying with Metadata Container")
        var allSuccess = true
        withMetadataContainer(session) { container =>
            val membersMap = (for(f <- files) yield {
                val member = ApexMember.getInstance(f, session)
                member.setMetadataContainerId(container.getId)
                (member, f)
            }).toMap

            val waitTimeMilliSecs = config.getProperty("pollWaitMillis").getOrElse("" + (ONE_SECOND * 3)).toInt
            val saveResults = session.createTooling(membersMap.map(_._1.asInstanceOf[SObject]).toArray)
            val res = saveResults.head
            if (res.isSuccess) {
                val request = new ContainerAsyncRequest()
                request.setIsCheckOnly(session.getConfig.isCheckOnly)
                request.setMetadataContainerId(container.getId)
                val requestResults = session.createTooling(Array(request))
                for (res <- requestResults) {
                    if (res.isSuccess) {
                        val requestId = res.getId
                        val soql = "SELECT Id, State, DeployDetails, ErrorMsg FROM ContainerAsyncRequest where id = '" + requestId + "'"
                        val asyncQueryResult = session.queryTooling(soql)
                        if (asyncQueryResult.getSize > 0) {
                            var _request = asyncQueryResult.getRecords.head.asInstanceOf[ContainerAsyncRequest]
                            var lastReportTime = System.currentTimeMillis()
                            var attempts = 0
                            while ("Queued" == _request.getState) {
                                val reportAttempt = (System.currentTimeMillis() - lastReportTime) > (ONE_SECOND * 3)
                                blocking {
                                    Thread.sleep(waitTimeMilliSecs)
                                    _request = session.queryTooling(soql).getRecords.head.asInstanceOf[ContainerAsyncRequest]
                                }
                                //report only once every 3 seconds
                                if (reportAttempt) {
                                    logger.info("waiting result, poll #" + attempts)
                                    lastReportTime = System.currentTimeMillis()
                                } else {
                                    logger.trace("waiting result, poll #" + attempts)
                                }
                                attempts += 1
                            }
                            allSuccess &= processSaveResult(_request, membersMap, updateSessionDataOnSuccess)
                        }
                    } else {
                        throw new IllegalStateException("Failed to create ContainerAsyncRequest. " + res.getErrors.head.getMessage)
                    }
                }
            } else {
                throw new IllegalStateException("Failed to create Apex Member(s). " + res.getErrors.head.getMessage)
            }


        }

        session.storeSessionData()
        allSuccess
    }

    /**
     * when using Tooling try to avoid Metadata.retrieve() as it may be quite slow
     * this method will override its parent in ApexDeploy and uses simple query() calls to load LastModifiedDate
     * @param files - list of files to check for conflicts
     * @return
     */
    override def getFilesNewerOnRemote(files: List[File]): Option[List[Map[String, Any]]] = {
        Try(getFilesModificationData(files)) match {
            case Success(modificationDataByFile) =>
                val dataOrNone: List[Option[Map[String, Any]]] = for (file <- files) yield {
                    modificationDataByFile.get(file)  match {
                        case Some(data) =>
                            val localMillis = ZuluTime.deserialize(data("Local-LastModifiedDateStr").toString).getTimeInMillis
                            val remoteMillis = ZuluTime.deserialize(data("Remote-LastModifiedDateStr").toString).getTimeInMillis

                            if (localMillis < remoteMillis) Some(data) else None

                        case None => None
                    }
                }
                val res = dataOrNone.filter(_ != None).map(_.get)
                if (res.isEmpty) None else Some(res)
            case Failure(e) =>
                // looks like we can not query some of the components being deployed/saved using SOQL
                // fall back to metadata Retrieve call
                super.getFilesNewerOnRemote(files)
        }

    }
    /**
     * find remote modification Date + ById for all provided files
     * @param files - list of files to retrieve data for
     * @return
     */
    private def getFilesModificationData(files: List[File]): Map[File, Map[String, Any]] = {
        val filesByXmlType = files.groupBy(f => {
            val isAuraFile = AuraMember.isSupportedType(f)
            val xmlType = if (isAuraFile) {
                AuraMember.XML_TYPE
            } else {
                DescribeMetadata.getXmlNameBySuffix(session, FileUtils.getExtension(f)).getOrElse("")
            }
            xmlType
        }).filterNot(p => p._1.isEmpty) //here we are making sure that there are not files with empty xml types

        val dataByFileCol = filesByXmlType.keys.par.map(xmlType => {
            val res = getFilesModificationData(xmlType, filesByXmlType(xmlType))
            res
        })

        //now convert ParIterable of maps into a single map
        val dataByFile = dataByFileCol.foldLeft(Map[File, Map[String, Any]] ())(_ ++ _)

        dataByFile.toMap
    }

    /**
     * retrieve modification data for single XML Type
     * @param xmlType, e.g. ApexClass
     * @param filesOfSameType - all files MUST be of the same XML Type
     * @return
     */
    private def getFilesModificationData(xmlType: String, filesOfSameType: List[File]): Map[File, Map[String, Any]] = {
        val fileById = collection.mutable.HashMap[String, File]()
        for (file <- filesOfSameType) {
            val key = session.getKeyByFile(file)
            session.getData(key).get("Id")  match {
                case Some(id) => fileById += id.toString -> file
                case None =>
            }
        }
        val ids = fileById.keys
        if (ids.nonEmpty) {
            val queryResult = session.query("select Id, LastModifiedDate, LastModifiedBy.Name, LastModifiedById from " + xmlType
                + " where Id in (" + ids.map("'" + _ + "'").mkString(",") + ")")
            val records = queryResult.getRecords

            val dataByFile = records.map(record => {
                val file = fileById(record.getId)
                //2014-02-24T20:35:59.000Z
                val lastModifiedStr = record.getField("LastModifiedDate").toString
                val lastModifiedDate = ZuluTime.deserialize(lastModifiedStr)
                val millsLocal = session.getData(session.getKeyByFile(file)).getOrElse("LastModifiedDateMills", 0).toString.toLong

                file -> Map(
                    "file" -> file,
                    "Id" -> record.getId,
                    "LastModifiedByName" -> record.getField("LastModifiedBy").asInstanceOf[XmlObject].getChild("Name").getValue,
                    "LastModifiedById" -> record.getField("LastModifiedById"),
                    "Remote-LastModifiedDateStr" -> ZuluTime.formatDateGMT(lastModifiedDate),
                    "Local-LastModifiedDateStr" -> ZuluTime.formatDateGMT(ZuluTime.toCalendar(millsLocal))
                )

            })
            dataByFile.toMap
        } else {
            Map()
        }
    }

    private def processSaveResult(request: ContainerAsyncRequest, membersMap: Map[ApexMember, File], updateSessionDataOnSuccess: Boolean): Boolean = {

        request.getState match {
            case "Completed" =>
                logger.debug("Request succeeded")
                if (updateSessionDataOnSuccess) {
                    val modificationDataByFile = getFilesModificationData(membersMap.values.toList)
                    for (member <- membersMap.keys) {

                        val f = membersMap(member)
                        modificationDataByFile.get(f)  match {
                            case Some(data) =>
                                val key = session.getKeyByFile(f)
                                val lastModifiedDate = ZuluTime.deserialize(data("Remote-LastModifiedDateStr").toString)
                                val newData = MetadataType.getValueMap(config, f, member.xmlType, Some(member.getContentEntityId), lastModifiedDate, fileMeta = None)
                                val oldData = session.getData(key)
                                session.setData(key, oldData ++ newData)
                            case None =>
                        }

                    }
                }
                //dump session data to disk
                session.storeSessionData()

                config.responseWriter.println("RESULT=SUCCESS")
                config.responseWriter.println("FILE_COUNT=" + membersMap.size)
                if (!config.isCheckOnly) {
                    config.responseWriter.startSection("SAVED FILES")
                    membersMap.values.foreach(f => config.responseWriter.println(f.getName))
                    config.responseWriter.endSection("SAVED FILES")
                }
                true

            case "Failed" =>
                logger.debug("Request failed")
                responseWriter.println("RESULT=FAILURE")
                config.responseWriter.startSection("ERROR LIST")
                val deployDetails = request.getDeployDetails
                if (deployDetails.getComponentFailures.nonEmpty ) {
                    val deployMessages = deployDetails.getComponentFailures
                    logger.debug(deployMessages)
                    //display errors both as messages and as ERROR: lines
                    val componentFailureMessage = new Message(ResponseWriter.WARN, "Compiler errors")
                    responseWriter.println(componentFailureMessage)

                    for (deployMessage <- deployMessages) {
                        val line = deployMessage.getLineNumber
                        val column = deployMessage.getColumnNumber
                        val problem = deployMessage.getProblem
                        val fName = deployMessage.getFileName match {
                            case null => null
                            case x =>
                                //workaround for Tooling API bug - getFileName() returns file label instead of file name
                                //replace all non letter/digit with underscore and make sure that
                                //- it does not contain two consecutive underscores
                                //- does not end with underscore
                                x.replaceAll("[^a-zA-Z0-9]", "_").replaceAll("(_)\\1+", "$1").replaceAll("_*$", "")
                        }//getFileName ?
                        val xmlType = deployMessage.getComponentType
                        val describeMetadataResult = DescribeMetadata.getMap(session).get(xmlType).get
                        val extension = describeMetadataResult.getSuffix
                        val filePath =  if (!extension.isEmpty) session.getRelativeFilePath(fName, extension) match {
                            case Some(_filePath) => _filePath
                            case _ => ""
                        }

                        val problemType = "CompileError"
                        responseWriter.println("ERROR", Map("type" -> problemType, "line" -> line, "column" -> column, "filePath" -> filePath, "text" -> problem))
                        responseWriter.println(new MessageDetail(componentFailureMessage, Map("type" -> problemType, "filePath" -> filePath, "text" -> problem)))
                    }
                    false
                } else {
                    //general error
                    //display errors both as messages and as ERROR: lines
                    val generalFailureMessage = new Message(ResponseWriter.WARN, "General failure")
                    responseWriter.println(generalFailureMessage)
                    val problem = request.getErrorMsg match {
                        case "Can't alter metadata in an active org" => //attempt to deploy using Tooling API into Production
                            request.getErrorMsg + "; If you are trying to deploy using Tooling API in Production org then switch to Metadata API."
                        case s => s
                    }
                    responseWriter.println("ERROR", Map("type" -> "Error", "text" -> problem))
                    responseWriter.println(new MessageDetail(generalFailureMessage, Map("type" -> "Error", "text" -> problem)))
                }
                false

            case state =>
                logger.error("Request Failed with status: " + state)
                throw new IllegalStateException("Failed to send Async Request. Status= " + state)
        }
    }
}


class SaveSpecificFiles extends SaveModified {
    private val superActionHelp = super.getHelp
    override def getHelp: ActionHelp = new AbstractActionHelp(superActionHelp) {
        override def getExample: String =
            """
              |same as --deploySpecificFiles but uses Tooling API and hence only accepts files of *same* type and status.
              |i.e. --saveSpecificFiles can be used for 1 or more Class Files or 1 or more Trigger files, but not for a mixture of classes and triggers
              |All files must be either New or Existing, but not a mixture of new and existing files.
              |If you have a mixture then you may need to make 2 saveSpecificFiles calls: 1 for New and 1 for Existing files, or use --deploySpecificFiles instead
              |
              |Suppose we want to save AccountHandler.cls and AccountHandlerTest.cls, content of file passed to --specificFiles
              |may look like so
              |-------------------------------
              |src/classes/AccountHandler.cls
              |src/classes/AccountHandlerTest.cls
              |-------------------------------
              |
              |then in addition to all normal command line parameters you add
              |... --specificFiles=/tmp/file_list.txt
            """.stripMargin

        override def getName: String = "saveSpecificFiles"

        override def getSummary: String = "action uses file list specified in a file and sends update() Tooling API Based call"

        override def getParamNames: List[String] = List("updateSessionDataOnSuccess", "specificFiles") ++ getParentActionHelp.getParamNames

        override def getParamDescription(paramName: String): String = paramName match {
            case "updateSessionDataOnSuccess" => "--updateSessionDataOnSuccess=true|false (defaults to false) - if true then update session data if deployment is successful"
            case "specificFiles" => "--specificFiles=/path/to/file with file list"
            case _ => getParentActionHelp.getParamDescription(paramName)
        }
    }

    /**
     * list files specified in --specificFiles parameter
     */
    protected override def getFiles:List[File] = {
        val config = session.getConfig
        //load file list from specified file
        val fileListFile = new File(config.getRequiredProperty("specificFiles").get)
        session.listApexFilesFromFile(fileListFile)
    }

    protected override def reportEmptyFileList(files: List[File]): Unit = {
        responseWriter.println("RESULT=FAILURE")
        val fileListFile = new File(config.getRequiredProperty("specificFiles").get)
        responseWriter.println(new Message(ResponseWriter.ERROR, "no valid files in " + fileListFile))
    }
}
