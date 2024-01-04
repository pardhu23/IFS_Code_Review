package ifscodereview;

import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class CommentGenerator {
    private final List<Comment> comments;

    public CommentGenerator() {
        comments = new ArrayList<>();
    }

    public void identifyIssue(String body, String filePath, int lineNumber, String commitID) {
        comments.add(new Comment(body, filePath, lineNumber, commitID));
    }

    public void writeCommentsToFile(String filePath) {
        String commentsJSON = generateCommentsJSON();

        try (FileWriter file = new FileWriter(filePath)) {
            file.write(commentsJSON);
            System.out.println("Comments have been written to " + filePath);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private String generateCommentsJSON() {
        StringBuilder jsonBuilder = new StringBuilder("[");
        for (int i = 0; i < comments.size(); i++) {
            Comment comment = comments.get(i);
            jsonBuilder.append("{");
            jsonBuilder.append("\"body\": \"").append(comment.getBody()).append("\",");
            jsonBuilder.append("\"path\": \"").append(comment.getFilePath().replace("\\", "\\\\")).append("\",");
            jsonBuilder.append("\"position\": ").append(comment.getLineNumber()).append(",");
            jsonBuilder.append("\"commit_id\": \"").append(comment.getCommitID()).append("\"");
            jsonBuilder.append("}");

            if (i < comments.size() - 1) {
                jsonBuilder.append(",");
            }
        }
        jsonBuilder.append("]");

        return jsonBuilder.toString();
    }

    // Inner class to represent a comment
    private static class Comment {
        private final String body;
        private final String filePath;
        private final int lineNumber;
        private final String commitID;

        public Comment(String body, String filePath, int lineNumber, String commitID) {
            this.body = body;
            this.filePath = filePath;
            this.lineNumber = lineNumber;
            this.commitID = commitID;
        }

        public String getBody() {
            return body;
        }

        public String getFilePath() {
            return filePath;
        }

        public int getLineNumber() {
            return lineNumber;
        }

        public String getCommitID() {
            return commitID;
        }
    }
}
