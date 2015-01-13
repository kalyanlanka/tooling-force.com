package com.neowit.apex.completion

import com.neowit.apex.Session
import com.neowit.apex.parser.antlr.SoqlParser._
import com.neowit.apex.parser.antlr.{SoqlParser, SoqlLexer}
import com.neowit.apex.parser.{SoqlTreeListener, ApexParserUtils, ApexTree, Member}
import org.antlr.v4.runtime.tree.{ParseTree, ParseTreeWalker}
import org.antlr.v4.runtime.{ParserRuleContext, CommonTokenStream, ANTLRInputStream, Token}
import scala.collection.JavaConversions._

class SoqlCompletionResult(val options: List[Member], val isSoqlStatement: Boolean)

class SoqlAutoComplete (token: Token, line: Int, column: Int, cachedTree: ApexTree, session: Session) {

    def listOptions:SoqlCompletionResult = {

        val soqlString = stripBrackets(token.getText)
        val expressionTokens = getCaretStatement(soqlString)
        if (expressionTokens.isEmpty) {
            //looks like the file is too broken to get to the point where caret resides
            return new SoqlCompletionResult(List(), isSoqlStatement = true)
        }


        val input = new ANTLRInputStream(soqlString)
        val lexer = new SoqlLexer(input)
        val tokens = new CommonTokenStream(lexer)
        val parser = new SoqlParser(tokens)
        ApexParserUtils.removeConsoleErrorListener(parser)
        val tree = parser.soqlStatement()
        val walker = new ParseTreeWalker()
        val extractor = new SoqlTreeListener(parser, line, column)
        walker.walk(extractor, tree)

        val finalContext = expressionTokens.head.finalContext
        val definition = findSymbolType(expressionTokens, tree)

        definition match {
          case Some(soqlTypeMember) =>
              if (expressionTokens.size > 1)
                      new SoqlCompletionResult(removeDuplicates(resolveExpression(soqlTypeMember, expressionTokens.tail), finalContext), isSoqlStatement = true)
                  else
                      new SoqlCompletionResult(removeDuplicates(resolveExpression(soqlTypeMember, expressionTokens), finalContext), isSoqlStatement = true)
          case None =>
              println("Failed to find definition")
              new SoqlCompletionResult(Nil, isSoqlStatement = false)
        }
    }



    private def resolveExpression(parentType: Member, expressionTokens: List[AToken]): List[Member] = {
        //TODO
        if (Nil == expressionTokens) {
            return parentType.getChildren
        }
        val token: AToken = expressionTokens.head
        if (token.symbol.isEmpty) {
            return parentType.getChildren
        }
        val tokensToGo = expressionTokens.tail
        parentType.getChild(token.symbol) match {
            case Some(_childMember) =>
                resolveExpression(_childMember, tokensToGo)
            case None if tokensToGo.isEmpty => //parent does not have a child with this identity, return partial match
                val partialMatchChildren = filterByPrefix(parentType.getChildren, token.symbol)
                if (partialMatchChildren.isEmpty) {
                    //TODO
                    //return getApexTypeMembers(token.symbol)
                } else {
                    return partialMatchChildren
                }
            case _ => //check if parentType has child which has displayable identity == token.symbol
        }

        Nil
    }

    private def filterByPrefix(members: List[Member], prefix: String): List[Member] = {
        members.filter(_.getIdentity.toLowerCase.startsWith(prefix.toLowerCase))
    }

    /**
     * using Apex token and line/column of caret in currently edited file convert these to coordinates inside SOQL string
     * @param token - which contains SOQL expression [select ...]
     * @param line - original caret line num in the source file
     * @param column - original caret column in the source file
     * @return - index of caret in SOQL string
     */
    private def getCaretPositionInSoql(token: Token, line: Int, column: Int): (Int, Int) = {
        val caretLine = line - token.getLine
        val caretColumn = if (line == token.getLine ) {
            //SOQL starts in the middle of Apex line
            column - token.getCharPositionInLine
        } else {
            column
        }
        (caretLine, caretColumn)
    }

    /**
     * @param text - convert '[select ...]' into 'select ...'
     * @return
     */
    private def stripBrackets(text: String): String = {
        if (text.startsWith("[") && text.endsWith("]")) {
            text.substring(1, text.length - 1)
        } else {
            text
        }
    }

    private def getCaretStatement(soqlString: String): List[AToken] = {
        val (caretLine, caretColumn) = getCaretPositionInSoql(token, line, column)

        val caret = new CaretInString(caretLine + 1, caretColumn, soqlString)
        val input = new ANTLRInputStream(soqlString)
        val tokenSource = new CodeCompletionTokenSource(new SoqlLexer(input), caret)
        val tokens: CommonTokenStream = new CommonTokenStream(tokenSource)  //Actual
        //val tokens: CommonTokenStream = new CommonTokenStream(getLexer(file))
        val parser = new SoqlParser(tokens)
        ApexParserUtils.removeConsoleErrorListener(parser)
        parser.setBuildParseTree(true)
        parser.setErrorHandler(new CompletionErrorStrategy())

        //parse tree until we reach caret caretAToken
        try {
            parser.soqlStatement()
        } catch {
            case ex: CaretReachedException =>
                return CompletionUtils.breakExpressionToATokens(ex)
            case e:Throwable =>
                println(e.getMessage)
        }

        List()

    }

    /**
     * select Id, <caret> from Account
     *
     * @return typeMember - in the above example: typeMember will be FromTypeMember - "Account"
     */
    private def findSymbolType(expressionTokens: List[AToken], tree: SoqlStatementContext ): Option[Member] = {

        def getFromMember(ctx: ParserRuleContext): Option[SoqlMember] = {
            getFrom(ctx) match {
                case x :: xs => Some(x)
                case _ => None

            }
        }

        expressionTokens.head.finalContext match {
            case ctx: SelectItemContext =>
                //started new field in Select part
                getFromMember(tree)
            case ctx: FieldItemContext =>
                //part of field (most likely trying to complete relationship)
                getFromMember(tree)
            case ctx: AggregateFunctionContext =>
                //started new field inside aggregate function which has a variant without argument
                getFromMember(tree)
            case ctx: FieldNameContext if ctx.getParent.isInstanceOf[AggregateFunctionContext] =>
                //started new field inside aggregate function which requires an argument
                getFromMember(tree)
            case ctx: ObjectTypeContext if ctx.getParent.isInstanceOf[FromStatementContext] =>
                //looks like caret is just after 'FROM' keyword
                Some(new DBModelMember(session))

            case _ => None //TODO
        }
    }

    private def getFrom(ctx: ParserRuleContext): List[FromTypeMember] = {

        val soqlStatementContextOption = if (ctx.isInstanceOf[SoqlStatementContext]) Some(ctx) else ApexParserUtils.getParent(ctx, classOf[SoqlStatementContext])
        soqlStatementContextOption match {
            case Some(soqlStatement) =>
                val objTypeMembers = ApexParserUtils.findChild(soqlStatement, classOf[FromStatementContext]) match {
                  case Some(fromStatement) => fromStatement.objectType().map(new FromTypeMember(_, session))
                  case None => Nil
                }
                objTypeMembers.toList
            case None => Nil
        }
    }

    /**
     * if caret represents field name, then remove all already entered (simple) field names from list of options
     * @param members - member list to reduce
     * @param finalContext - caret context
     * @return - reduced list of members
     */
    private def removeDuplicates(members: List[Member], finalContext: ParseTree): List[Member] = {
        //need to keep relationship fields because these can be useful more than once in the same SELECT statement
        def isReferenceMember(m: Member): Boolean = {
            m.isInstanceOf[SObjectFieldMember] && m.asInstanceOf[SObjectFieldMember].isReference
        }
        finalContext match {
            case ctx: SelectItemContext if ctx.getParent.isInstanceOf[SelectStatementContext] =>
                //started new field in Select part
                val existingFieldNames = ApexParserUtils.findChildren(ctx.getParent, classOf[FieldNameContext]).map(fNameNode => fNameNode.Identifier(0).toString).toSet
                members.filter(m => !existingFieldNames.contains(m.getIdentity) || isReferenceMember(m))

            case _ => Nil
        }

    }
}

class CaretInString(line:  Int, column: Int, str: String) extends Caret (line, column){
    def getOffset: Int = {
        ApexParserUtils.getOffset(str, line, column)
    }
}

trait SoqlMember extends Member {
    override def isStatic: Boolean = false
}

class FromTypeMember(ctx: ObjectTypeContext, session: Session) extends SoqlMember {
    override def getIdentity: String = ctx.Identifier().getSymbol.getText

    override def getType: String = getIdentity

    override def getSignature: String = getIdentity

    override def getChildren: List[Member] = DatabaseModel.getModelBySession(session) match {
        case Some(dbModel) => dbModel.getSObjectMember(getIdentity) match {
            case Some(sobjectMember) =>
                sobjectMember.getChildren
            case None => Nil
        }
        case None => Nil
    }
}

class DBModelMember(session: Session) extends Member {
    /**
     * @return
     * for class it is class name
     * for method it is method name + string of parameter types
     * for variable it is variable name
     * etc
     */
    override def getIdentity: String = "APEX_DB_MODEL"

    override def getType: String = getIdentity

    override def getSignature: String = getIdentity

    override def isStatic: Boolean = true

    override def getChildren: List[Member] = DatabaseModel.getModelBySession(session) match {
        case Some(dbModel) => dbModel.getSObjectMembers
        case None => Nil
    }

    override def getChild(identity: String, withHierarchy: Boolean): Option[Member] = {
        DatabaseModel.getModelBySession(session) match {
            case Some(dbModel) => dbModel.getSObjectMember(getIdentity)
            case None => None
        }
    }
}