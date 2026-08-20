import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class App {
    private static final List<Todo> todos = new ArrayList<>();
    private static final List<Todo> trash = new ArrayList<>();
    private static int nextId = 1;
    private static String notificationEmail = "";

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
                redirect(exchange);
                return;
            } else if (path.equals("/settings") && exchange.getRequestMethod().equals("POST")) {
                String formData = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                notificationEmail = URLDecoder.decode(
                        formData.substring(formData.indexOf("email=") + 6),
                        StandardCharsets.UTF_8);
                redirect(exchange);
                return;
            } else if (path.equals("/toggle")) {
                changeDone(exchange);
                redirect(exchange);
                return;
            } else if (path.equals("/delete")) {
                moveToTrash(exchange);
                redirect(exchange);
                return;
            } else if (path.equals("/restore")) {
                restoreFromTrash(exchange);
                redirect(exchange);
                return;
            } else if (path.equals("/")) {
                String html = "<!DOCTYPE html><html><head><meta charset='UTF-8'>"
                        + "<title>わたしのTodo</title>"
                        + "<style>"
                        + "body { max-width: 760px; margin: 24px auto; padding: 0 16px; font-size: 16px; "
                        + "font-family: 'MS UI Gothic', 'ＭＳ Ｐゴシック', sans-serif; background: #008080; color: #000; }"
                        + "main { background: #c0c0c0; border: 3px outset #eee; padding: 14px; }"
                        + "h1 { color: #fff; background: #000080; padding: 8px; font-size: 24px; margin: 0 0 14px; }"
                        + "fieldset { border: 2px groove #fff; margin: 12px 0; }"
                        + "input[type=text], input[type=email] { width: 280px; }"
                        + "a { color: #000080; }"
                        + "li { margin: 8px 0; }"
                        + ".notice { background: #ffffcc; border: 1px dashed #000; padding: 6px; }"
                        + "</style></head><body><main>"
                        + "<h1>★ わたしのTodo ★</h1>"
                        + "<p class='notice'>ようこそ！ 今日もこつこつ片づけよう！</p>"
                        + "<form method='post' action='/add'>"
                        + "<input type='text' name='todo' placeholder='やることを入力'>"
                        + "<button type='submit'>追加</button></form>"
                        + "<h2>タスク一覧</h2><ul>";

                if (todos.isEmpty()) {
                    html += "</ul><p>タスクはありません</p>";
                } else {
                    for (Todo todo : todos) {
                        html += "<li><form method='get' action='/toggle' style='display:inline'>"
                                + "<input type='hidden' name='id' value='" + todo.getId() + "'>"
                                + "<input type='checkbox' onchange='this.form.submit()'"
                                + (todo.isDone() ? " checked" : "") + ">"
                                + "</form> " + todo.getTitle()
                                + " <a href='/delete?id=" + todo.getId() + "'>削除</a></li>";
                    }
                    html += "</ul>";
                }

                html += "<h2>通知設定</h2>"
                        + "<form method='post' action='/settings'>"
                        + "<label>通知先メールアドレス: "
                        + "<input type='email' name='email' value='" + notificationEmail + "'></label>"
                        + "<button type='submit'>保存</button></form>";
                if (!notificationEmail.isEmpty()) {
                    html += "<p><a href='mailto:" + notificationEmail
                            + "?subject=Todo%E9%80%9A%E7%9F%A5&body=Todo%E3%81%AE%E9%80%9A%E7%9F%A5%E3%81%A7%E3%81%99'>"
                            + "メール通知を作成</a>（メールソフトから送信）</p>";
                }
                html += "<p><button type='button' onclick='enableBrowserNotification()'>"
                        + "ブラウザ通知を許可</button> "
                        + "<button type='button' onclick='testBrowserNotification()'>通知をテスト</button></p>"
                        + "<h2>ゴミ箱</h2>"
                        + "<button type='button' onclick='toggleTrash()'>表示 / 非表示</button>"
                        + "<div id='trash' style='display:none'><ul>";

                if (trash.isEmpty()) {
                    html += "<li>ゴミ箱は空です</li>";
                } else {
                    for (Todo todo : trash) {
                        html += "<li>" + todo.getTitle()
                                + " <a href='/restore?id=" + todo.getId() + "'>復元</a></li>";
                    }
                }

                html += "</ul></div>"
                        + "<script>"
                        + "function toggleTrash(){var x=document.getElementById('trash');"
                        + "x.style.display=x.style.display==='none'?'block':'none';}"
                        + "function enableBrowserNotification(){"
                        + "if('Notification' in window){Notification.requestPermission();}"
                        + "else{alert('このブラウザは通知に対応していません');}}"
                        + "function testBrowserNotification(){"
                        + "if('Notification' in window && Notification.permission==='granted'){"
                        + "new Notification('Todo通知',{body:'通知のテストです'});}"
                        + "else{alert('先に「ブラウザ通知を許可」を押してください');}}"
                        + "</script></main></body></html>";
                message = html;
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

    private static void changeDone(com.sun.net.httpserver.HttpExchange exchange) {
        Integer id = readId(exchange);
        if (id != null) {
            for (Todo todo : todos) {
                if (todo.getId() == id) {
                    todo.setDone(!todo.isDone());
                    break;
                }
            }
        }
    }

    private static void moveToTrash(com.sun.net.httpserver.HttpExchange exchange) {
        Integer id = readId(exchange);
        if (id != null) {
            for (int i = 0; i < todos.size(); i++) {
                if (todos.get(i).getId() == id) {
                    trash.add(todos.remove(i));
                    break;
                }
            }
        }
    }

    private static void restoreFromTrash(com.sun.net.httpserver.HttpExchange exchange) {
        Integer id = readId(exchange);
        if (id != null) {
            for (int i = 0; i < trash.size(); i++) {
                if (trash.get(i).getId() == id) {
                    todos.add(trash.remove(i));
                    break;
                }
            }
        }
    }

    private static Integer readId(com.sun.net.httpserver.HttpExchange exchange) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null || !query.startsWith("id=")) {
            return null;
        }
        try {
            return Integer.parseInt(query.substring(3));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void redirect(com.sun.net.httpserver.HttpExchange exchange) throws java.io.IOException {
        exchange.getResponseHeaders().set("Location", "/");
        exchange.sendResponseHeaders(303, -1);
        exchange.getResponseBody().close();
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
