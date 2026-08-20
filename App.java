import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class App {
    private static final List<Todo> todos = new ArrayList<>();
    private static int nextId = 1;

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        todos.add(new Todo(nextId++, "牛乳を買う", false));
        todos.add(new Todo(nextId++, "卵を買う", true));

        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String message;
            String contentType = "text/plain; charset=UTF-8";

            if (path.equals("/hello")) {
                String query = exchange.getRequestURI().getRawQuery();
                String name = query.substring(query.indexOf("name=") + 5);
                message = "こんにちは、" + name + "さん！";
            } else if (path.equals("/bye")) {
                message = "さようなら！";
            } else if (path.equals("/add") && exchange.getRequestMethod().equals("POST")) {
                String formData = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String todoTitle = URLDecoder.decode(formData.substring(formData.indexOf("todo=") + 5), StandardCharsets.UTF_8);
                if (!todoTitle.isEmpty()) {
                    todos.add(new Todo(nextId++, todoTitle, false));
                }
                exchange.getResponseHeaders().set("Location", "/");
                exchange.sendResponseHeaders(303, -1);
                exchange.getResponseBody().close();
                return;
            } else if (path.equals("/done") || path.equals("/delete")) {
                String query = exchange.getRequestURI().getRawQuery();
                if (query != null && query.startsWith("id=")) {
                    try {
                        int id = Integer.parseInt(query.substring(3));
                        for (int i = 0; i < todos.size(); i++) {
                            Todo todo = todos.get(i);
                            if (todo.getId() == id) {
                                if (path.equals("/done")) {
                                    todo.setDone(true);
                                } else {
                                    todos.remove(i);
                                }
                                break;
                            }
                        }
                    } catch (NumberFormatException e) {
                    }
                }
                exchange.getResponseHeaders().set("Location", "/");
                exchange.sendResponseHeaders(303, -1);
                exchange.getResponseBody().close();
                return;
            } else if (path.equals("/")) {
                String html = "<!DOCTYPE html><html><head><meta charset='UTF-8'>"
                        + "<style>body { max-width: 600px; margin: 20px auto; font-size: 16px; }"
                        + "input { width: 300px; }</style></head><body>"
                        + "<h1>わたしのTodo</h1>"
                        + "<form method='post' action='/add'>"
                        + "<input type='text' name='todo'>"
                        + "<button type='submit'>追加</button></form>"
                        + "<ul>";
                if (todos.isEmpty()) {
                    html += "</ul><p>やることは、いまゼロです</p>";
                } else {
                    for (Todo todo : todos) {
                        html += "<li>" + todo.getTitle() + (todo.isDone() ? " ✔" : "")
                                + " <a href='/done?id=" + todo.getId() + "'>完了</a>"
                                + " <a href='/delete?id=" + todo.getId() + "'>削除</a></li>";
                    }
                    html += "</ul>";
                }
                message = html + "</body></html>";
                contentType = "text/html; charset=UTF-8";
            } else {
                message = "ページが見つかりません";
            }

            byte[] body = message.getBytes(StandardCharsets.UTF_8);

            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });
        server.start();
        System.out.println("サーバー起動: http://localhost:8080");
    }
}

class Todo {
    private int id;
    private String title;
    private boolean done;

    Todo(int id, String title, boolean done) {
        this.id = id;
        this.title = title;
        this.done = done;
    }

    int getId() {
        return id;
    }

    String getTitle() {
        return title;
    }

    boolean isDone() {
        return done;
    }

    void setDone(boolean done) {
        this.done = done;
    }
}
