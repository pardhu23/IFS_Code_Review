# IFS_Code_Review
# IFS PL/SQL Code Review Tool

This tool is designed to perform code reviews on IFS PL/SQL files. It helps identify issues, validate naming conventions, and generate comments for code quality improvements.

## Features

- Procedure Name Validation: Validates if the procedure names adhere to IFS naming guidelines.

- Parameter Naming Checks: Verifies the correctness of parameter names within procedures.

- Parameter Direction Validation: Ensures proper declaration and ordering of parameter directions (IN, OUT, IN OUT).

- Parameter Underscore Convention: Checks if parameters end with an underscore as per conventions.

- Cursor and Variable Declaration Order: Verifies the order of cursor and variable declarations within procedures.

- SELECT Statement Validation:
  - Detects the use of SELECT *, prompting users to specify required columns.
  - Checks for proper formatting and alignment of selected columns.
  
- Oracle Built-in Function Naming Conventions: Identifies Oracle built-in functions and ensures they are in uppercase.

- Column Alias Validation: Validates the formatting of column aliases within SELECT statements.

- Table Reference Extraction: Extracts table references used in the PL/SQL code.

- Integration with GitHub Pull Requests: Integrates comments generated from code analysis into GitHub pull requests.

## Usage

To use the IFS PL/SQL Code Review Tool, follow these steps:

1. **Clone the Repository:**
    ```bash
    git clone <repository_url>
    ```

2. **Compile the Code:**
    Compile the Java code using a Java compiler or an IDE of your choice.

3. **Run the Tool:**
    Execute the tool by running the main class `IfsCodeReview.java` and passing the necessary arguments.

    ```bash
    java IfsCodeReview <commit_SHA> <file_path> <owner> <repo> <pull_number>
    ```

    Replace `<commit_SHA>`, `<file_path>`, `<owner>`, `<repo>`, and `<pull_number>` with your specific values.

## Requirements

- Java Development Kit (JDK)
- PL/SQL files to analyze
- GitHub repository details for commenting on pull requests
