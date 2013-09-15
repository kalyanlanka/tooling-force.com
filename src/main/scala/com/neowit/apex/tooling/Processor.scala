/*
 * Copyright (c) 2013 Andrey Gavrikov.
 * this file is part of tooling-force.com application
 * https://github.com/neowit/tooling-force.com
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.neowit.apex.tooling

import java.io.File
import com.sforce.soap.tooling._
import scala.{Array, Some}

object ZuluTime {
    val zulu = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
    zulu.setTimeZone(java.util.TimeZone.getTimeZone("GMT"))
    def format(d: java.util.Date):String = zulu.format(d)
    def parse(s: String): java.util.Date = zulu.parse(s)

}

trait ProcessorBase extends Logging {
    def isFile(resourcePath: String) = {
        val f = new File(resourcePath)
        f.isFile && f.canRead
    }
    def isDirectory(resourcePath: String) = {
        val f = new File(resourcePath)
        f.isDirectory && f.canRead
    }
    def getName(resourcePath: String) = {
        val f = new File(resourcePath)
        f.getName
    }

    def getBody(resourcePath: String): String = {
        scala.io.Source.fromFile(resourcePath).getLines().mkString("\n")
    }

}
trait Processor extends ProcessorBase {
    def save(session: SfdcSession, sessionData: SessionData)

    //protected def loadMetadata(session: SfdcSession)
    //protected def isLoaded: Boolean
    //protected val typeName: String
}

object Processor {
    val containerName = "tooling-force.com"
    def getProcessor(resourcePath: String): Processor = {
        val processor = resourcePath match {
            case ClassProcessor(className, ext) => new ClassProcessor(resourcePath, className)
            case PackageProcessor(srcDir) => new PackageProcessor(srcDir)
            case _ => throw new ConfigValueException("Invalid resource path: " + resourcePath)
        }
        processor
    }

}

class ClassProcessor(resourcePath: String, className: String) extends Processor {

    protected val typeName: String  = "ApexClass"
    protected def isLoaded = false
    def update(session: SfdcSession) = {
    }
    def create(session: SfdcSession, sessionData: SessionData) = {
        val apexClass = new ApexClass()
        apexClass.setBody(getBody(resourcePath))
        val saveResults = session.create(Array(apexClass))
        for (res <- saveResults) {
            if (res.isSuccess) {
                //store Id in session

                sessionData.setField(apexClass.getName, "Id", res.getId)//TODO - fix key
            }
            if (!res.getSuccess) {
                if (StatusCode.DUPLICATE_VALUE == res.getErrors.head.getStatusCode) {
                    //file already exists, try to update

                }

                logger.error("" + res.getErrors.head)

            }
        }
    }
    def save(session: SfdcSession, sessionData: SessionData) {
        //TODO
        /*
        getId(session, className) match {
          case Some(x) => update(session)
          case None => create(session, sessionData)
        }
        */
    }

}
object ClassProcessor extends ProcessorBase {
    def unapply(resourcePath: String): Option[(String, String)] = {
        if (isFile(resourcePath) && resourcePath.endsWith(".cls")) {
            val nameWithExt = getName(resourcePath)
            Option((nameWithExt.substring(0, nameWithExt.length - ".cls".length), ".cls"))
        } else {
            None
        }
    }
}

class PackageProcessor(srcDir: File) extends Processor {

    def save(session: SfdcSession, sessionData: SessionData) {
        val changedFileMap = getChangedFiles(sessionData)
        //add each file into MetadataContainer and if container update is successful then record serverMills
        //in session data as LastSyncDate for each of successful files
        val container = new MetadataContainer()
        container.setName(Processor.containerName)
        sessionData.getField("MetadataContainer", "Name") match {
            case Some(x) if Processor.containerName == x => //container exists, do nothing
            case _ => //container needs to be created
                val containerSaveResults: Array[SaveResult] = session.create(Array(container))
                if (!containerSaveResults.head.isSuccess) {
                    throw new IllegalStateException("Failed to create Metadata Container. " + containerSaveResults.head.getErrors.head.getMessage)
                } else {
                    sessionData.setData("MetadataContainer", Map("Name" -> Processor.containerName, "Id" -> containerSaveResults.head.getId))
                    sessionData.store()
                }
        }
        //iterate through changedFileMap and return list of ApexComponentMember objects
        val members = for (helper <- changedFileMap.keys; f <- changedFileMap(helper)) yield {
            helper.getMemberInstance(sessionData, f)
        }
        saveApexMembers(session, sessionData, container, members)
    }

    private def saveApexMembers(session: SfdcSession, sessionData: SessionData, container: MetadataContainer,
                                         members: Iterable[SObject]) {

        val serverMills = session.getServerTimestamp.getTimestamp.getTimeInMillis
        val saveResults = session.create(members.toArray)
        for (res <- saveResults) {
            if (res.isSuccess) {
                val request = new ContainerAsyncRequest()
                request.setIsCheckOnly(false)
                request.setMetadataContainer(container)
                val requestResults = session.create(Array(request))
                for (res <- requestResults) {
                    if (res.isSuccess) {
                        val requestId = res.getId
                        val soql = "SELECT Id, State, CompilerErrors, ErrorMsg FROM ContainerAsyncRequest where id = '" + requestId + "'"
                        val asyncQueryResult = session.query(soql)
                        if (asyncQueryResult.getSize > 0) {
                            var _request = asyncQueryResult.getRecords.head.asInstanceOf[ContainerAsyncRequest]
                            while ("Queued" == _request.getState) {
                                Thread.sleep(2000)
                                _request = session.query(soql).getRecords.head.asInstanceOf[ContainerAsyncRequest]
                            }
                            processSaveResult(sessionData, _request, members, serverMills)
                        }
                    }
                }
            } else {
                throw new IllegalStateException("Failed to create Metadata Container. " + res.getErrors.head.getMessage)
            }
        }
        sessionData.store()
    }

    private def processSaveResult(sessionData: SessionData, request: ContainerAsyncRequest, members: Iterable[SObject], serverMills: Long) {

        request.getState match {
            case "Completed" =>
                logger.debug("Request succeeded")
                for(m <- members) {
                    val id = Member.toMember(m).getContentEntityId
                    sessionData.getKeyById(id) match {
                        case Some(key) =>
                            sessionData.setField(key, "LastSyncDate", serverMills.toString)
                        case None => throw new IllegalStateException("Failed to fetch session data for " + Processor.containerName + " using Id=" + id)
                    }
                }
            case state =>
                logger.error("Request Failed with status: " + state)
                throw new IllegalStateException("Failed to send Async Request. Status= " + state)
        }
    }
    /**
     * using stored session data figure out what files have changed
     * @return
     */
    type ChangedFiles = Map[TypeHelper, List[File]]

    def getChangedFiles(sessionData: SessionData): ChangedFiles = {
        def iter (helpers: List[TypeHelper], res: ChangedFiles): ChangedFiles = {
            helpers match {
              case Nil => res
              case helper :: xs =>
                  val files = helper.listFiles(srcDir)
                  iter(xs, res ++ Map(helper -> files.filter(isModified(sessionData, helper, _)).toList))

            }
        }
        iter(TypeHelpers.list, Map())
    }
    def isModified(sessionData: SessionData, helper: TypeHelper, f: File): Boolean = {
        sessionData.getField(helper.getKey(f.getName), "LastSyncDate") match {
            case Some(x) =>
                f.lastModified() > ZuluTime.parse(x).getTime
            //case None => throw new IllegalStateException("Workspace is out of date. Please refresh before continuing")
            case _ => true
        }
    }
}

object PackageProcessor extends ProcessorBase {
    def unapply(resourcePath: String): Option[File] = {
        if (isDirectory(resourcePath) && resourcePath.matches(""".*src[\\|/]?$""")) {
            val srcDir = new File(resourcePath)
            Some(srcDir)
        } else {
            None
        }
    }
}
