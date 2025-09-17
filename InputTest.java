// A simple file manager and server using Java's built-in HTTP server
// Features: list files/folders, upload files, create folders, delete files/folders
// This file should be in exact this location only otherwise this will not work!! Location: "d:\college_practice\InputTest.java".


import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class InputTest {
    private static final String SHARED_DIR = "D:/college_practice";

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        server.createContext("/", new FileHandler());
        server.setExecutor(null);
        String localIp = getLocalIp();
        System.out.println("File server started at:");
        System.out.println("  Local:   http://localhost:8080/");
        if (localIp != null) {
            System.out.println("  Network: http://" + localIp + ":8080/");
        }
        server.start();
    }

    // Get the local network IP address
    private static String getLocalIp() {
        try {
            java.net.InetAddress local = java.net.InetAddress.getLocalHost();
            String ip = local.getHostAddress();
            if (!ip.equals("127.0.0.1")) return ip;
            // Try to find a non-loopback address
            java.util.Enumeration<java.net.NetworkInterface> nics = java.net.NetworkInterface.getNetworkInterfaces();
            while (nics.hasMoreElements()) {
                java.net.NetworkInterface nic = nics.nextElement();
                java.util.Enumeration<java.net.InetAddress> addrs = nic.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress addr = addrs.nextElement();
                    if (!addr.isLoopbackAddress() && addr instanceof java.net.Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {}
        return null;
    }

    static class FileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            String uriPath = URLDecoder.decode(exchange.getRequestURI().getPath(), "UTF-8");
            Path filePath = Paths.get(SHARED_DIR, uriPath).normalize();
            if (!filePath.startsWith(Paths.get(SHARED_DIR))) {
                sendResponse(exchange, 403, "Access Denied");
                return;
            }
            File file = filePath.toFile();

            // Handle file/folder deletion
            if (method.equalsIgnoreCase("POST") && exchange.getRequestURI().getQuery() != null) {
                String query = exchange.getRequestURI().getQuery();
                if (query.startsWith("delete=")) {
                    String delName = query.substring(7);
                    File delFile = new File(file, delName);
                    boolean deleted = delFile.isDirectory() ? deleteDirectory(delFile) : delFile.delete();
                    String msg = deleted ? "Deleted successfully." : "Failed to delete.";
                    sendRedirect(exchange, uriPath, msg);
                    return;
                }
            }

            // Handle folder creation
            if (method.equalsIgnoreCase("POST") && exchange.getRequestURI().getQuery() != null) {
                String query = exchange.getRequestURI().getQuery();
                if (query.startsWith("mkdir=")) {
                    String folderName = query.substring(6);
                    File newDir = new File(file, folderName);
                    boolean created = newDir.mkdir();
                    String msg = created ? "Folder created." : "Failed to create folder.";
                    sendRedirect(exchange, uriPath, msg);
                    return;
                }
            }

            // Handle file upload
            if (method.equalsIgnoreCase("POST") && exchange.getRequestHeaders().containsKey("Content-Type") && exchange.getRequestHeaders().getFirst("Content-Type").startsWith("multipart/form-data")) {
                String boundary = exchange.getRequestHeaders().getFirst("Content-Type").split("boundary=")[1];
                handleFileUpload(exchange, file, boundary);
                sendRedirect(exchange, uriPath, "File uploaded.");
                return;
            }

            if (file.isDirectory()) {
                StringBuilder response = new StringBuilder();
                response.append("<html><head><title>File Manager</title><style>body{font-family:sans-serif;background:#f4f4f4;}table{width:100%;border-collapse:collapse;}th,td{padding:8px 12px;}tr:nth-child(even){background:#eee;}a{color:#0074d9;text-decoration:none;}form{display:inline;}input[type=text]{padding:4px;}input[type=submit]{padding:4px 8px;}</style></head><body>");
                response.append("<h2>File Manager - ").append(uriPath).append("</h2>");
                // Folder creation form
                response.append("<form method='POST' action='?mkdir=' onsubmit=\"this.action='?mkdir='+encodeURIComponent(this.folder.value);\"><input type='text' name='folder' placeholder='New folder' required><input type='submit' value='Create Folder'></form> ");
                // File upload form
                response.append("<form method='POST' enctype='multipart/form-data'><input type='file' name='file' required><input type='submit' value='Upload'></form>");
                response.append("<table border='1'><tr><th>Name</th><th>Type</th><th>Size</th><th>Action</th></tr>");
                if (!uriPath.equals("/")) {
                    response.append("<tr><td><a href='../'>.. (parent directory)</a></td><td>Folder</td><td></td><td></td></tr>");
                }
                File[] files = file.listFiles();
                if (files != null) {
                    for (File f : files) {
                        String name = f.getName();
                        String link = uriPath.endsWith("/") ? uriPath + name : uriPath + "/" + name;
                        response.append("<tr>");
                        if (f.isDirectory()) {
                            response.append("<td><a href='" + link + "/'>" + name + "/</a></td><td>Folder</td><td></td>");
                        } else {
                            response.append("<td><a href='" + link + "'>" + name + "</a></td><td>File</td><td>" + f.length() + " bytes</td>");
                        }
                        // Delete button
                        response.append("<td><form method='POST' action='?delete=" + name + "' onsubmit=\"return confirm('Delete " + name + "?');\"><input type='submit' value='Delete'></form></td>");
                        response.append("</tr>");
                    }
                }
                response.append("</table></body></html>");
                sendResponse(exchange, 200, response.toString());
            } else if (file.isFile()) {
                String mime = Files.probeContentType(filePath);
                if (mime == null) mime = "application/octet-stream";
                exchange.getResponseHeaders().add("Content-Type", mime);
                exchange.getResponseHeaders().add("Content-Disposition", "attachment; filename=\"" + file.getName() + "\"");
                exchange.sendResponseHeaders(200, file.length());
                try (OutputStream os = exchange.getResponseBody(); FileInputStream fis = new FileInputStream(file)) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = fis.read(buffer)) != -1) {
                        os.write(buffer, 0, count);
                    }
                }
            } else {
                sendResponse(exchange, 404, "File Not Found");
            }
        }

        // Recursively delete directory
        private boolean deleteDirectory(File dir) {
            File[] allContents = dir.listFiles();
            if (allContents != null) {
                for (File file : allContents) {
                    if (file.isDirectory()) {
                        if (!deleteDirectory(file)) return false;
                    } else {
                        if (!file.delete()) return false;
                    }
                }
            }
            return dir.delete();
        }

        // Handle file upload (very basic, no filename sanitization)
        private void handleFileUpload(HttpExchange exchange, File dir, String boundary) throws IOException {
            InputStream is = exchange.getRequestBody();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int len;
            while ((len = is.read(buffer)) != -1) baos.write(buffer, 0, len);
            byte[] data = baos.toByteArray();
            String content = new String(data, "ISO-8859-1");
            String[] parts = content.split("--" + boundary);
            for (String part : parts) {
                if (part.contains("Content-Disposition")) {
                    int fnIdx = part.indexOf("filename=");
                    if (fnIdx == -1) continue;
                    String filename = part.substring(fnIdx + 10, part.indexOf('"', fnIdx + 10));
                    int dataIdx = part.indexOf("\r\n\r\n");
                    if (dataIdx == -1) continue;
                    byte[] fileData = part.substring(dataIdx + 4).replaceAll("\r\n$", "").getBytes("ISO-8859-1");
                    try (FileOutputStream fos = new FileOutputStream(new File(dir, filename))) {
                        fos.write(fileData);
                    }
                }
            }
        }

        private void sendResponse(HttpExchange exchange, int code, String response) throws IOException {
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(code, response.getBytes("UTF-8").length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes("UTF-8"));
            }
        }

        private void sendRedirect(HttpExchange exchange, String uriPath, String msg) throws IOException {
            exchange.getResponseHeaders().add("Location", uriPath + (uriPath.contains("?") ? "&" : "?") + "msg=" + java.net.URLEncoder.encode(msg, "UTF-8"));
            exchange.sendResponseHeaders(303, -1);
            exchange.close();
        }
    }
}