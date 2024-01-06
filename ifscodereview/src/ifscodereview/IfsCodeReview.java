package ifscodereview;

import org.antlr.v4.runtime.*;
import ifscodereview.grammar.*;
import org.antlr.v4.runtime.tree.ParseTreeWalker;
import org.antlr.v4.runtime.tree.ParseTree;
import ifscodereview.grammar.PlSqlParser;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.antlr.v4.runtime.misc.Interval;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 *
 * @author pardha
 */
public class IfsCodeReview extends PlSqlParserBaseListener {

   private static final String GITHUB_API_URL = "https://api.github.com/repos/{owner}/{repo}/pulls/{pull_number}/comments";

   private static String commitSHA = "";
   private static String filePath = "";
   private static CommentGenerator commentGenerator = new CommentGenerator();

   private static class TableReferenceInfo {

      private final String tableName;
      private final int lineNumber;

      public TableReferenceInfo(String tableName) {
         this.tableName = tableName;
         this.lineNumber = -1; // Default value for cases where line number is not provided
      }

      public TableReferenceInfo(String tableName, int lineNumber) {
         this.tableName = tableName;
         this.lineNumber = lineNumber;
      }

      public String getTableName() {
         return tableName;
      }

      public int getLineNumber() {
         return lineNumber;
      }
   }

   private static class RuleInfo {

      private final int lineNumber;
      private final int charPosition;

      public RuleInfo(int lineNumber, int charPosition) {
         this.lineNumber = lineNumber;
         this.charPosition = charPosition;
      }

      public int getLineNumber() {
         return lineNumber;
      }

      public int getCharPosition() {
         return charPosition;
      }
   }

   private static class ConsolidatedIssues {

      private final int lineNumber;
      private final String issueDetails;

      public ConsolidatedIssues(int lineNumber, String issueDetails) {
         this.lineNumber = lineNumber;
         this.issueDetails = issueDetails;
      }

      public int getLineNumber() {
         return lineNumber;
      }

      public String getIssueDetails() {
         return issueDetails;
      }
   }

   /**
    * This class extracts information from the code related to code checks.
    * It extends the PlSqlParserBaseListener provided by the ANTLR library.
    */
   private static class CodeCheckExtractor extends PlSqlParserBaseListener {

      private List<String> generatedProcedures = Arrays.asList("Update___", "Check_Common___", "Check_Update___");

      private final List<String> sqlStatements = new ArrayList<>();
      private final List<TableReferenceInfo> tableReferences = new ArrayList<>();
      private final String[] literalNames = PlSqlLexer.getLiteralNames();
      private List<RuleInfo> columnLineNumbers = new ArrayList<>();

      private List<ConsolidatedIssues> allIssues = new ArrayList<>();

      private int lineNumber = 0;

      /**
       * This method is called when entering a procedure name in the code.
       * It checks if the procedure name follows the IFS naming guidelines and generates a comment if it doesn't.
       *
       * @param ctx The context of the procedure name in the parse tree.
       */
      @Override
      public void enterProcedure_name(PlSqlParser.Procedure_nameContext ctx) {
         Token identifier = ctx.getStart();
         lineNumber = identifier.getLine();
         String procedureName = ctx.getText();
         if (!isCustomPascalCase(procedureName)) {
            commentGenerator.identifyIssue("Procedure name " + procedureName + " is not follow IFS naming guidelines", filePath, lineNumber, commitSHA);
         }
      }

      /**
       * This method is called when entering a function name in the code.
       * It checks if the function name follows the IFS naming guidelines and generates a comment if it doesn't.
       *
       * @param ctx The context of the function name in the parse tree.
       */
      @Override
      public void enterFunction_name(PlSqlParser.Function_nameContext ctx) {
         Token identifier = ctx.getStart();
         lineNumber = identifier.getLine();
         String functionName = ctx.getText();
         if (!isCustomPascalCase(functionName)) {
            commentGenerator.identifyIssue("Function name " + functionName + " is not follow IFS naming guidelines", filePath, lineNumber, commitSHA);
         }
      }

      /**
       * This method checks if a name follows the custom PascalCase naming convention used in IFS code.
       *
       * @param name The name to be checked.
       * @return true if the name follows the custom PascalCase convention, false otherwise.
       */
      private boolean isCustomPascalCase(String name) {
         if (name == null || name.isEmpty()) {
            return false;
         }

         char[] chars = name.toCharArray();
         boolean underscoreFound = false;
         int consecutiveUnderscores = 0;

         if (!Character.isUpperCase(chars[0])) {
            return false;
         }

         for (int i = 1; i < chars.length; i++) {
            char currentChar = chars[i];
            if (!Character.isLetterOrDigit(currentChar) && currentChar != '_') {
               return false;
            }

            if (currentChar == '_') {
               underscoreFound = true;
               consecutiveUnderscores++;
               if (consecutiveUnderscores > 3) {
                  return false; // More than 3 consecutive underscores
               }
            } else {
               if (underscoreFound) {
                  if (consecutiveUnderscores > 1) {
                     return false; // More than 3 consecutive underscores
                  }
                  consecutiveUnderscores = 0;
                  underscoreFound = false;

                  if (!Character.isUpperCase(currentChar)) {
                     return false;
                  }
               } else {
                  if (consecutiveUnderscores == 0 && Character.isUpperCase(currentChar)) {
                     return false; // Uppercase letter in the middle
                  }
                  if (!Character.isLowerCase(currentChar) && !Character.isDigit(currentChar)) {
                     return false;
                  }
               }
            }
         }
         return true;
      }

      /**
       * This method is called when entering a procedure body in the code.
       * It performs various checks related to the parameters and variables of the procedure.
       *
       * @param ctx The context of the procedure body in the parse tree.
       */
      @Override
      public void enterProcedure_body(PlSqlParser.Procedure_bodyContext ctx) {
         boolean inOutFound = false;
         boolean inFound = false;
         boolean inDefaultFound = false;

         List<RuleInfo> paramStartPositions = new ArrayList<>();
         List<RuleInfo> directionStartPositions = new ArrayList<>();
         List<RuleInfo> typeStartPositions = new ArrayList<>();

         String procedureName = ctx.procedure_name().getText();
         System.out.println("Procedure name: " + procedureName);

         // Track starting positions for parameters, directions, and data types
         List<PlSqlParser.ParameterContext> parameters = ctx.parameter();

         if (!parameters.isEmpty()) {
            for (PlSqlParser.ParameterContext parameter : parameters) {
               String paramName = parameter.parameter_name().getText();
               Token identifier = parameter.getStart();
               lineNumber = identifier.getLine();
               paramStartPositions.add(new RuleInfo(lineNumber, identifier.getCharPositionInLine()));

               List<PlSqlParser.Parameter_directionContext> directionContextList = parameter.parameter_direction();
               if (!directionContextList.isEmpty()) {
                  PlSqlParser.Parameter_directionContext directionContext = directionContextList.get(0);
                  directionStartPositions.add(new RuleInfo(lineNumber, directionContext.getStart().getCharPositionInLine()));

               } else {
                  // Validation failed: Parameter direction is missing
                  commentGenerator.identifyIssue(paramName + ": Parameter direction was not specified.", filePath, lineNumber, commitSHA);
               }

               PlSqlParser.Type_specContext typeSpec = parameter.type_spec();
               typeStartPositions.add(new RuleInfo(lineNumber, typeSpec.getStart().getCharPositionInLine()));

               if (!paramName.endsWith("_")) {
                  // Validation failed: Underscore is missing at the end of the parameter
                  commentGenerator.identifyIssue(paramName + ": Parameter does not end with an underscore", filePath, lineNumber, commitSHA);
               }

               if (!generatedProcedures.contains(procedureName)) {
                  String direction = parameter.parameter_direction(0) != null ? parameter.parameter_direction(0).getText() : "";
                  String defaultVal = parameter.default_value_part() != null ? parameter.default_value_part().getText() : "";

                  if (direction.equals("OUT")) {
                     if (inOutFound || inFound || inDefaultFound) {
                        // Validation failed: OUT parameter found after other types
                        commentGenerator.identifyIssue(paramName + ": OUT parameter found after other types", filePath, lineNumber, commitSHA);
                     }
                  } else if (direction.contains("IN OUT")) {
                     inOutFound = true;
                     if (inFound || inDefaultFound) {
                        // Validation failed: IN OUT parameter found after other types
                        commentGenerator.identifyIssue(paramName + ": IN OUT parameter found after other types", filePath, lineNumber, commitSHA);
                     }
                  } else if (direction.contains("IN") && !paramName.equals("objid_")) {
                     if (defaultVal.isEmpty()) {
                        inFound = true;
                        if (inDefaultFound) {
                           // Validation failed: IN parameter found after IN with default
                           commentGenerator.identifyIssue(paramName + ": IN parameter found after IN with default", filePath, lineNumber, commitSHA);
                        }
                     } else {
                        inDefaultFound = true;
                     }
                  }
               }
            }

            checkVerticalAlignment("Parameters", paramStartPositions);
            checkVerticalAlignment("Parameters Directions", directionStartPositions);
            checkVerticalAlignment("Parameters Data Types", typeStartPositions);
         }

         List<RuleInfo> variableStartPositions = new ArrayList<>();

         List<RuleInfo> varDatatypeStartPositions = new ArrayList<>();
         List<String> varCursorNames = new ArrayList<>();
         boolean cursorDeclared = false;  // Flag to track cursor declaration

         PlSqlParser.Seq_of_declare_specsContext seqOfDeclareSpecsContext = ctx.seq_of_declare_specs();

         if (seqOfDeclareSpecsContext != null) {
            List<PlSqlParser.Declare_specContext> declareSpecsList = seqOfDeclareSpecsContext.declare_spec();

            for (PlSqlParser.Declare_specContext declareSpec : declareSpecsList) {
               //PlSqlParser.IdentifierContext identifierContext = declareSpec.variable_declaration().identifier();

               PlSqlParser.Variable_declarationContext variableDeclarationContext = declareSpec.variable_declaration();
               if (variableDeclarationContext != null) {
                  Token identifier = variableDeclarationContext.getStart();
                  lineNumber = identifier.getLine();

                  PlSqlParser.IdentifierContext identifierContext = variableDeclarationContext.identifier();
                  variableStartPositions.add(new RuleInfo(lineNumber, identifierContext.getStart().getCharPositionInLine()));

                  PlSqlParser.Type_specContext varDatatype = declareSpec.variable_declaration().type_spec();
                  varDatatypeStartPositions.add(new RuleInfo(lineNumber, varDatatype.getStart().getCharPositionInLine()));

                  // Check if a cursor has been declared before the variable
                  if (cursorDeclared) {
                     for (String cursorName : varCursorNames) {
                        String rowTypeDataType = cursorName + "%ROWTYPE";
                        if (!varDatatype.getText().contains(rowTypeDataType)) {
                           commentGenerator.identifyIssue("Normal variable declarations should be before the cursor declarations.", filePath, lineNumber, commitSHA);
                           cursorDeclared = false;
                           break;
                        }
                     }
                  }
               }

               PlSqlParser.Cursor_declarationContext cursorDeclarationContext = declareSpec.cursor_declaration();
               if (cursorDeclarationContext != null) {
                  PlSqlParser.Cursor_nameContext cursorName = cursorDeclarationContext.cursor_name();
                  varCursorNames.add(cursorName.getText());

                  cursorDeclared = true;
               }
            }

            if (!variableStartPositions.isEmpty()) {
               checkVerticalAlignment("Variables", variableStartPositions);
               checkVerticalAlignment("Variable Data Types", varDatatypeStartPositions);
            }
         }

      }

      /**
       * This method is called when entering a function body in the code.
       * It performs various checks related to the parameters and variables of the function.
       *
       * @param ctx The context of the function body in the parse tree.
       */
      @Override
      public void enterFunction_body(PlSqlParser.Function_bodyContext ctx) {
         boolean inOutFound = false;
         boolean inFound = false;
         boolean inDefaultFound = false;

         List<RuleInfo> paramStartPositions = new ArrayList<>();
         List<RuleInfo> directionStartPositions = new ArrayList<>();
         List<RuleInfo> typeStartPositions = new ArrayList<>();

         String functionName = ctx.function_name().getText();
         System.out.println("Function name: " + functionName);

         // Track starting positions for parameters, directions, and data types
         List<PlSqlParser.ParameterContext> parameters = ctx.parameter();

         if (!parameters.isEmpty()) {
            for (PlSqlParser.ParameterContext parameter : parameters) {
               String paramName = parameter.parameter_name().getText();
               Token identifier = parameter.getStart();
               lineNumber = identifier.getLine();
               paramStartPositions.add(new RuleInfo(lineNumber, identifier.getCharPositionInLine()));

               List<PlSqlParser.Parameter_directionContext> directionContextList = parameter.parameter_direction();
               if (!directionContextList.isEmpty()) {
                  PlSqlParser.Parameter_directionContext directionContext = directionContextList.get(0);
                  directionStartPositions.add(new RuleInfo(lineNumber, directionContext.getStart().getCharPositionInLine()));

               } else {
                  // Validation failed: Parameter direction is missing
                  commentGenerator.identifyIssue(paramName + ": Parameter direction was not specified.", filePath, lineNumber, commitSHA);
               }

               PlSqlParser.Type_specContext typeSpec = parameter.type_spec();
               typeStartPositions.add(new RuleInfo(lineNumber, typeSpec.getStart().getCharPositionInLine()));

               if (!paramName.endsWith("_")) {
                  // Validation failed: Underscore is missing at the end of the parameter
                  commentGenerator.identifyIssue(paramName + ": Parameter does not end with an underscore", filePath, lineNumber, commitSHA);
               }

               if (!generatedProcedures.contains(functionName)) {
                  String direction = parameter.parameter_direction(0) != null ? parameter.parameter_direction(0).getText() : "";
                  String defaultVal = parameter.default_value_part() != null ? parameter.default_value_part().getText() : "";

                  if (direction.equals("OUT")) {
                     if (inOutFound || inFound || inDefaultFound) {
                        // Validation failed: OUT parameter found after other types
                        commentGenerator.identifyIssue(paramName + ": OUT parameter found after other types", filePath, lineNumber, commitSHA);
                     }
                  } else if (direction.contains("IN OUT")) {
                     inOutFound = true;
                     if (inFound || inDefaultFound) {
                        // Validation failed: IN OUT parameter found after other types
                        commentGenerator.identifyIssue(paramName + ": IN OUT parameter found after other types", filePath, lineNumber, commitSHA);
                     }
                  } else if (direction.contains("IN") && !paramName.equals("objid_")) {
                     if (defaultVal.isEmpty()) {
                        inFound = true;
                        if (inDefaultFound) {
                           // Validation failed: IN parameter found after IN with default
                           commentGenerator.identifyIssue(paramName + ": IN parameter found after IN with default", filePath, lineNumber, commitSHA);
                        }
                     } else {
                        inDefaultFound = true;
                     }
                  }
               }
            }

            checkVerticalAlignment("Parameters", paramStartPositions);
            checkVerticalAlignment("Parameters Directions", directionStartPositions);
            checkVerticalAlignment("Parameters Data Types", typeStartPositions);
         }
      }

      /**
       * This method checks the vertical alignment of a list of code elements.
       * It generates a comment if the elements are not vertically aligned.
       *
       * @param category The category of the code elements being checked.
       * @param columnInfoList The list of code elements and their line numbers.
       */
      private void checkVerticalAlignment(String category, List<RuleInfo> columnInfoList) {
         if (columnInfoList.isEmpty()) {
            // Handle the case when the list is empty
            return;
         }

         int expectedColumn = columnInfoList.get(0).getCharPosition();

         for (int i = 1; i < columnInfoList.size(); i++) {
            RuleInfo currentColumnInfo = columnInfoList.get(i);
            int currentPosition = currentColumnInfo.getCharPosition();

            if (currentPosition != expectedColumn) {
               commentGenerator.identifyIssue(category + " are not vertically aligned", filePath, currentColumnInfo.lineNumber, commitSHA);
               break;
            }
         }
      }

      @Override
      public void exitSelected_list(PlSqlParser.Selected_listContext ctx) {
         Token identifier = ctx.getStart();
         lineNumber = identifier.getLine();

         if (ctx.ASTERISK() != null) {
            //commentGenerator.identifyIssue("SELECT * is not allowed, specificy the required columns.", filePath, lineNumber, commitSHA);
            allIssues.add(new ConsolidatedIssues(lineNumber, "SELECT * is not allowed, specificy the required columns."));

         } else {
            checkSelectColumnLineNumbers("SELECT columns", columnLineNumbers);
            columnLineNumbers.clear();
         }
      }

      private void checkSelectColumnLineNumbers(String category, List<RuleInfo> columnInfoList) {
         for (int i = 1; i < columnInfoList.size(); i++) {
            RuleInfo currentColumnInfo = columnInfoList.get(i);
            RuleInfo previousColumnInfo = columnInfoList.get(i - 1);

            int currentLineNumber = currentColumnInfo.getLineNumber();
            int previousLineNumber = previousColumnInfo.getLineNumber();

            if (currentLineNumber == previousLineNumber) {
               //commentGenerator.identifyIssue(category + " should be one per line.", filePath, currentLineNumber, commitSHA);
               allIssues.add(new ConsolidatedIssues(currentLineNumber, category + " should be one per line."));
               break;
            }
         }
      }

      @Override
      public void exitCursor_declaration(PlSqlParser.Cursor_declarationContext ctx) {
         String allIssueDetails = "";
         if (!ctx.cursor_name().getText().equals(ctx.cursor_name().getText().toLowerCase())) {
            //commentGenerator.identifyIssue("Cursor name '" + ctx.cursor_name().getText() + "' should be in lowercase", filePath, ctx.cursor_name().getStart().getLine(), commitSHA);
            allIssues.add(new ConsolidatedIssues(ctx.cursor_name().getStart().getLine(), "Cursor name '" + ctx.cursor_name().getText() + "' should be in lowercase"));
         }
         for (TableReferenceInfo info : tableReferences) {
            if (!info.getTableName().equals(info.getTableName().toLowerCase())) {
               //commentGenerator.identifyIssue("Table name '" + info.getTableName() + "' should be in lowercase", filePath, info.getLineNumber(), commitSHA);
               allIssues.add(new ConsolidatedIssues(info.getLineNumber(), "Table name '" + info.getTableName() + "' should be in lowercase"));
            }
         }
         tableReferences.clear();

         for (int i = 0; i < allIssues.size(); i++) {
            ConsolidatedIssues issue = allIssues.get(i);
            allIssueDetails = allIssueDetails + "Line No: " + issue.getLineNumber() + " :- " + issue.issueDetails + "\\n";

         }

         if (allIssueDetails.length() > 0) {
            commentGenerator.identifyIssue("Issues in Cursor:\\n" + allIssueDetails, filePath, ctx.getStart().getLine(), commitSHA);
         }
         allIssues.clear();
      }

      @Override
      public void enterSelect_list_elements(PlSqlParser.Select_list_elementsContext ctx) {
         Token identifier = ctx.getStart();
         lineNumber = identifier.getLine();

         if (ctx.expression() != null) {
            // Handle individual expression
            String columnName = ctx.expression().getText();
            System.out.println("Column: " + columnName);

            Pattern pattern = Pattern.compile("(?<!\\.)\\b(\\w+)\\(");
            Matcher matcher = pattern.matcher(columnName);

            while (matcher.find()) {
               String substringBeforeParenthesis = matcher.group(1);

               if (Arrays.asList(literalNames).contains("'" + substringBeforeParenthesis.toUpperCase().trim() + "'") && !substringBeforeParenthesis.equals(substringBeforeParenthesis.toUpperCase())) {
                  //commentGenerator.identifyIssue(substringBeforeParenthesis + ": Oracle build-in function should be in uppercase", filePath, lineNumber, commitSHA);
                  allIssues.add(new ConsolidatedIssues(lineNumber, substringBeforeParenthesis + ": Oracle build-in function should be in uppercase"));
               }
            }
            // Check for column alias
            if (ctx.column_alias() != null) {
               String columnAlias = ctx.column_alias().identifier().getText();
               if (!columnAlias.equals(columnAlias.toLowerCase())) {
                  //commentGenerator.identifyIssue(columnAlias + " : column alias should be in lowercase", filePath, lineNumber, commitSHA);
                  allIssues.add(new ConsolidatedIssues(lineNumber, columnAlias + " : column alias should be in lowercase"));

               }
            }
            columnLineNumbers.add(new RuleInfo(lineNumber, ctx.expression().getStart().getCharPositionInLine()));
         } else if (ctx.getText().endsWith(".*")) {
            //commentGenerator.identifyIssue("SELECT * is not allowed, specificy the required columns.", filePath, lineNumber, commitSHA);
            allIssues.add(new ConsolidatedIssues(lineNumber, "SELECT * is not allowed, specificy the required columns."));

         }
      }

      @Override
      public void enterInsert_statement(PlSqlParser.Insert_statementContext ctx) {
         Token insertToken = ctx.getStart();
         int lineNumber = insertToken.getLine();
         commentGenerator.identifyIssue("INSERT statement found", filePath, lineNumber, commitSHA);
      }

      @Override
      public void enterUpdate_statement(PlSqlParser.Update_statementContext ctx) {
         Token updateToken = ctx.getStart();
         int lineNumber = updateToken.getLine();
         commentGenerator.identifyIssue("UPDATE statement found", filePath, lineNumber, commitSHA);
      }

      @Override
      public void enterDelete_statement(PlSqlParser.Delete_statementContext ctx) {
         Token deleteToken = ctx.getStart();
         int lineNumber = deleteToken.getLine();
         commentGenerator.identifyIssue("DELETE statement found", filePath, lineNumber, commitSHA);
      }

      @Override
      public void exitTable_ref(PlSqlParser.Table_refContext ctx) {
         TableRefExtractListener tableRefExtractListener = new TableRefExtractListener();
         ParseTreeWalker.DEFAULT.walk(tableRefExtractListener, ctx);
         tableReferences.addAll(tableRefExtractListener.getTableReferences(ctx.getStart().getLine()));
      }

      public List<TableReferenceInfo> getTableReferences() {
         return tableReferences;
      }

      private static class TableRefExtractListener extends PlSqlParserBaseListener {

         private List<TableReferenceInfo> tableReferences = new ArrayList<>();

         @Override
         public void enterTable_ref_aux(PlSqlParser.Table_ref_auxContext ctx) {
            TableRefAuxInternalExtractListener internalListener = new TableRefAuxInternalExtractListener();
            ParseTreeWalker.DEFAULT.walk(internalListener, ctx.table_ref_aux_internal());
            tableReferences.addAll(internalListener.getTableReferences(ctx.getStart().getLine()));
         }

         public List<TableReferenceInfo> getTableReferences(int lineNumber) {
            List<TableReferenceInfo> updatedList = new ArrayList<>();
            for (TableReferenceInfo info : tableReferences) {
               updatedList.add(new TableReferenceInfo(info.getTableName(), lineNumber));
            }
            return updatedList;
         }
      }

      private static class TableRefAuxInternalExtractListener extends PlSqlParserBaseListener {

         private List<TableReferenceInfo> tableReferences = new ArrayList<>();

         @Override
         public void enterTable_ref_aux_internal_one(PlSqlParser.Table_ref_aux_internal_oneContext ctx) {
            if (ctx != null && ctx.dml_table_expression_clause() != null && ctx.dml_table_expression_clause().tableview_name() != null) {
               Token tableNameToken = ctx.dml_table_expression_clause().tableview_name().getStart();
               tableReferences.add(new TableReferenceInfo(tableNameToken.getText()));
            }
         }

         public List<TableReferenceInfo> getTableReferences(int lineNumber) {
            List<TableReferenceInfo> updatedList = new ArrayList<>();
            for (TableReferenceInfo info : tableReferences) {
               updatedList.add(new TableReferenceInfo(info.getTableName(), lineNumber));
            }
            return updatedList;
         }
      }

      private static class SelectedListExtractListener extends PlSqlParserBaseListener {

         private boolean containsAsterisk = false;
         private int lineNumber;

         public SelectedListExtractListener() {
            this.lineNumber = 0;
         }

         @Override
         public void enterSelected_list(PlSqlParser.Selected_listContext ctx) {
            Token selectToken = ctx.getStart();
            lineNumber = selectToken.getLine();
            containsAsterisk = ctx.getText().equals("*");
         }

         public boolean containsAsterisk() {
            return containsAsterisk;
         }

         public int getLineNumber() {
            return lineNumber;
         }
      }

      private String getOriginalText(ParserRuleContext ctx) {
         Token startToken = ctx.getStart();
         Token stopToken = ctx.getStop();
         if (startToken != null && stopToken != null) {
            int startIndex = startToken.getStartIndex();
            int stopIndex = stopToken.getStopIndex();
            return startToken.getInputStream().getText(new Interval(startIndex, stopIndex));
         }
         return "";
      }
   }

   public static void main(String[] args) {
      String owner = "";
      String repo = "";
      int pullNumber = 0;

      if (args.length > 0) {
         commitSHA = args[0];
         filePath = args[1];
         owner = args[2];
         repo = args[3];
         pullNumber = Integer.parseInt(args[4]);
      }

      filePath = "C:\\Users\\pardh\\Documents\\Netbeans\\IfsCodeReview\\src\\ifscodereview\\CCrpObjectReservation.plsql"; // Replace with your file path

      try {
         File file = new File(filePath);

         // Use try-with-resources to automatically close resources
         try ( FileReader reader = new FileReader(file);  BufferedReader bufferedReader = new BufferedReader(reader)) {

            StringBuilder stringBuilder = new StringBuilder();
            String line;
            while ((line = bufferedReader.readLine()) != null) {
               stringBuilder.append(line).append(System.lineSeparator());
            }
            String plSqlCode = stringBuilder.toString();

            PlSqlLexer lexer = new PlSqlLexer(CharStreams.fromString(plSqlCode));
            CommonTokenStream tokens = new CommonTokenStream(lexer);
            PlSqlParser parser = new PlSqlParser(tokens);
            ParseTree tree = parser.sql_script();

            CodeCheckExtractor extractor = new CodeCheckExtractor();
            ParseTreeWalker.DEFAULT.walk(extractor, tree);

            // Print table references with line numbers
            System.out.println("Table References:");

            // Write comments to a JSON file
            commentGenerator.writeCommentsToFile("comments.json");

            String token = System.getenv("GH_TOKEN");

            if (token != null && !token.isBlank()) {
               String apiUrl = GITHUB_API_URL.replace("{owner}", owner)
                       .replace("{repo}", repo)
                       .replace("{pull_number}", String.valueOf(pullNumber));

               try {
                  // Read the JSON data from the file
                  String jsonString = new String(Files.readAllBytes(Paths.get("comments.json")));

                  // Parse the JSON array
                  JSONArray jsonArray = new JSONArray(jsonString);

                  // Loop through the array items
                  for (int i = 0; i < jsonArray.length(); i++) {
                     JSONObject jsonObject = jsonArray.getJSONObject(i);

                     System.out.println(jsonObject.toString());
                     try {
                        HttpClient client = HttpClient.newHttpClient();
                        HttpRequest request = HttpRequest.newBuilder()
                                .uri(URI.create(apiUrl))
                                .header("Authorization", "Bearer " + token)
                                .header("Accept", "application/vnd.github.v3+json")
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(jsonObject.toString()))
                                .build();

                        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

                        // Handle the response as needed
                        System.out.println(response.statusCode());
                        System.out.println(response.body());
                     } catch (Exception e) {
                        e.printStackTrace();
                     }
                  }
               } catch (IOException e) {
               }
            }

         } catch (IOException e) {
            e.printStackTrace();
         }
      } catch (Exception e) {
         e.printStackTrace();
      }
   }
}
