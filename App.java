import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

public class App {
    private static final List<Todo> todos = new ArrayList<>();
    private static final List<Todo> trash = new ArrayList<>();
    private static final Path DATA_FILE = Path.of("todo-data.txt");
    private static int nextId = 1;
    private static String notificationEmail = "";

    public static void main(String[] args) throws Exception {
        loadData();

        if (todos.isEmpty() && trash.isEmpty() && !Files.exists(DATA_FILE)) {
            todos.add(new Todo(nextId++, "牛乳を買う", false, ""));
            todos.add(new Todo(nextId++, "卵を買う", true, ""));
            saveData();
        }

        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            String message;
            String contentType = "text/plain; charset=UTF-8";

            if (path.equals("/hello")) {
                String query = exchange.getRequestURI().getRawQuery();
                String name = query.substring(query.indexOf("name=") + 5);
                message = "こんにちは、" + name + "さん！";
            } else if (path.equals("/bye")) {
                message = "さようなら！";
            } else if (path.equals("/notifications")) {
                message = dueNotificationsJson();
                contentType = "application/json; charset=UTF-8";
            } else if (path.equals("/add") && method.equals("POST")) {
                String formData = readBody(exchange);
                String title = formValue(formData, "todo");
                String dueDateTime = validDueDateTime(formValue(formData, "due"));
                if (isValidTitle(title) && dueDateTime != null) {
                    todos.add(new Todo(nextId++, title, false, dueDateTime));
                    saveData();
                }
                redirect(exchange);
                return;
            } else if (path.equals("/settings") && method.equals("POST")) {
                String formData = readBody(exchange);
                String email = formValue(formData, "email");
                notificationEmail = isValidEmail(email) ? email : "";
                saveData();
                redirect(exchange);
                return;
            } else if (path.equals("/toggle")) {
                changeDone(exchange);
                saveData();
                redirect(exchange);
                return;
            } else if (path.equals("/delete")) {
                moveToTrash(exchange);
                saveData();
                redirect(exchange);
                return;
            } else if (path.equals("/restore")) {
                restoreFromTrash(exchange);
                saveData();
                redirect(exchange);
                return;
            } else if (path.equals("/edit") && method.equals("GET")) {
                message = editPage(readId(exchange));
                contentType = "text/html; charset=UTF-8";
            } else if (path.equals("/edit") && method.equals("POST")) {
                editTodo(readBody(exchange));
                saveData();
                redirect(exchange);
                return;
            } else if (path.equals("/")) {
                message = homePage();
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

    private static String homePage() {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head><meta charset='UTF-8'>")
                .append("<title>わたしのTodo</title><style>")
                .append("body{max-width:760px;margin:24px auto;padding:0 16px;font-size:16px;")
                .append("font-family:'MS UI Gothic','ＭＳ Ｐゴシック',sans-serif;background:#008080;color:#000}")
                .append("main{background:#c0c0c0;border:3px outset #eee;padding:14px}")
                .append("h1{color:#fff;background:#000080;padding:8px;font-size:24px;margin:0 0 14px}")
                .append("input[type=text],input[type=email],input[type=datetime-local]{width:280px}")
                .append("a{color:#000080}li{margin:8px 0}.notice{background:#ffffcc;border:1px dashed #000;padding:6px}")
                .append("</style></head><body><main>")
                .append("<h1>★ わたしのTodo ★</h1>")
                .append("<p class='notice'>ようこそ！ 今日もこつこつ片づけよう！</p>")
                .append("<form method='post' action='/add'>")
                .append("<input type='text' name='todo' maxlength='200' placeholder='やることを入力' required>")
                .append("<input type='datetime-local' name='due'>")
                .append("<button type='submit'>追加</button></form>")
                .append("<h2>タスク一覧</h2><ul>");

        if (todos.isEmpty()) {
            html.append("</ul><p>タスクはありません</p>");
        } else {
            for (Todo todo : todos) {
                html.append(todoHtml(todo));
            }
            html.append("</ul>");
        }

        html.append("<h2>通知設定</h2>")
                .append("<form method='post' action='/settings'>")
                .append("<label>通知先メールアドレス: <input type='email' name='email' value='")
                .append(htmlEscape(notificationEmail)).append("'></label>")
                .append("<button type='submit'>保存</button></form>");

        if (!notificationEmail.isEmpty()) {
            html.append("<p><a href='mailto:").append(htmlEscape(notificationEmail))
                    .append("?subject=Todo%E9%80%9A%E7%9F%A5&body=Todo%E3%81%AE%E9%80%9A%E7%9F%A5%E3%81%A7%E3%81%99'>")
                    .append("メール通知を作成</a>（メールソフトから送信）</p>");
        }

        html.append("<p><button type='button' onclick='enableBrowserNotification()'>ブラウザ通知を許可</button> ")
                .append("<button type='button' onclick='testBrowserNotification()'>通知をテスト</button></p>")
                .append("<h2>ゴミ箱</h2>")
                .append("<button type='button' onclick='toggleTrash()'>表示 / 非表示</button>")
                .append("<div id='trash' style='display:none'><ul>");

        if (trash.isEmpty()) {
            html.append("<li>ゴミ箱は空です</li>");
        } else {
            for (Todo todo : trash) {
                html.append("<li>").append(htmlEscape(todo.getTitle()))
                        .append(" <a href='/restore?id=").append(todo.getId()).append("'>復元</a></li>");
            }
        }

        html.append("</ul></div><script>")
                .append("function toggleTrash(){var x=document.getElementById('trash');")
                .append("x.style.display=x.style.display==='none'?'block':'none';}")
                .append("function enableBrowserNotification(){")
                .append("if('Notification' in window){Notification.requestPermission().then(checkNotifications);}")
                .append("else{alert('このブラウザは通知に対応していません');}}")
                .append("function testBrowserNotification(){")
                .append("if('Notification' in window&&Notification.permission==='granted'){")
                .append("new Notification('Todo通知',{body:'通知のテストです'});}")
                .append("else{alert('先に「ブラウザ通知を許可」を押してください');}}")
                .append("function checkNotifications(){")
                .append("if(!('Notification' in window)||Notification.permission!=='granted')return;")
                .append("fetch('/notifications').then(function(r){return r.json();}).then(function(items){")
                .append("var sent=JSON.parse(localStorage.getItem('todoNotifications')||'{}');")
                .append("items.forEach(function(item){if(!sent[item.id]){")
                .append("new Notification('Todoの期限',{body:item.title});sent[item.id]=true;}});")
                .append("localStorage.setItem('todoNotifications',JSON.stringify(sent));});}")
                .append("setInterval(checkNotifications,60000);checkNotifications();")
                .append("</script></main></body></html>");

        return html.toString();
    }

    private static String todoHtml(Todo todo) {
        String due = todo.getDueDate().isEmpty() ? "" : "（期限: "
                + htmlEscape(displayDateTime(todo.getDueDate())) + "）";
        return "<li><form method='get' action='/toggle' style='display:inline'>"
                + "<input type='hidden' name='id' value='" + todo.getId() + "'>"
                + "<input type='checkbox' onchange='this.form.submit()'"
                + (todo.isDone() ? " checked" : "") + "></form> "
                + htmlEscape(todo.getTitle()) + due
                + " <a href='/edit?id=" + todo.getId() + "'>編集</a>"
                + " <a href='/delete?id=" + todo.getId() + "'>削除</a></li>";
    }

    private static String editPage(Integer id) {
        Todo target = findById(todos, id);
        if (target == null) {
            return "<!DOCTYPE html><html><body><p>Todoが見つかりません</p><a href='/'>戻る</a></body></html>";
        }
        return "<!DOCTYPE html><html><head><meta charset='UTF-8'><title>Todo編集</title></head><body>"
                + "<h1>Todoを編集</h1><form method='post' action='/edit'>"
                + "<input type='hidden' name='id' value='" + target.getId() + "'>"
                + "<input type='text' name='todo' maxlength='200' value='"
                + htmlEscape(target.getTitle()) + "' required>"
                + "<input type='datetime-local' name='due' value='"
                + htmlEscape(target.getDueDate()) + "'>"
                + "<button type='submit'>保存</button></form><p><a href='/'>戻る</a></p></body></html>";
    }

    private static String dueNotificationsJson() {
        StringBuilder json = new StringBuilder("[");
        LocalDateTime now = LocalDateTime.now();
        boolean first = true;
        for (Todo todo : todos) {
            if (!todo.isDone() && !todo.getDueDate().isEmpty()) {
                try {
                    if (!LocalDateTime.parse(todo.getDueDate()).isAfter(now)) {
                        if (!first) {
                            json.append(",");
                        }
                        json.append("{\"id\":").append(todo.getId())
                                .append(",\"title\":\"").append(jsonEscape(todo.getTitle())).append("\"}");
                        first = false;
                    }
                } catch (DateTimeParseException e) {
                    // 壊れた期限は通知対象にしない
                }
            }
        }
        return json.append("]").toString();
    }

    private static void editTodo(String formData) {
        Integer id = parseId(formValue(formData, "id"));
        String title = formValue(formData, "todo");
        String dueDateTime = validDueDateTime(formValue(formData, "due"));
        Todo target = findById(todos, id);
        if (target != null && isValidTitle(title) && dueDateTime != null) {
            target.setTitle(title);
            target.setDueDate(dueDateTime);
        }
    }

    private static void changeDone(HttpExchange exchange) {
        Todo todo = findById(todos, readId(exchange));
        if (todo != null) {
            todo.setDone(!todo.isDone());
        }
    }

    private static void moveToTrash(HttpExchange exchange) {
        Todo todo = findById(todos, readId(exchange));
        if (todo != null) {
            todos.remove(todo);
            trash.add(todo);
        }
    }

    private static void restoreFromTrash(HttpExchange exchange) {
        Todo todo = findById(trash, readId(exchange));
        if (todo != null) {
            trash.remove(todo);
            todos.add(todo);
        }
    }

    private static Todo findById(List<Todo> list, Integer id) {
        if (id == null) {
            return null;
        }
        for (Todo todo : list) {
            if (todo.getId() == id) {
                return todo;
            }
        }
        return null;
    }

    private static Integer readId(HttpExchange exchange) {
        return parseId(formValue(exchange.getRequestURI().getRawQuery(), "id"));
    }

    private static Integer parseId(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static String formValue(String formData, String key) {
        if (formData == null) {
            return "";
        }
        for (String part : formData.split("&")) {
            String[] pair = part.split("=", 2);
            if (pair.length == 2 && pair[0].equals(key)) {
                return URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    private static boolean isValidTitle(String title) {
        return title != null && !title.trim().isEmpty() && title.length() <= 200;
    }

    private static String validDueDateTime(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        try {
            LocalDateTime.parse(value);
            return value;
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String normalizeStoredDateTime(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        try {
            if (value.length() == 10) {
                return LocalDate.parse(value).atTime(23, 59).toString();
            }
            return LocalDateTime.parse(value).toString();
        } catch (DateTimeParseException e) {
            return "";
        }
    }

    private static String displayDateTime(String value) {
        return value.replace("T", " ");
    }

    private static boolean isValidEmail(String email) {
        return email != null && email.matches("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    }

    private static String htmlEscape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    private static void redirect(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Location", "/");
        exchange.sendResponseHeaders(303, -1);
        exchange.getResponseBody().close();
    }

    private static void loadData() throws IOException {
        if (!Files.exists(DATA_FILE)) {
            return;
        }
        todos.clear();
        trash.clear();
        for (String line : Files.readAllLines(DATA_FILE, StandardCharsets.UTF_8)) {
            if (line.startsWith("E|")) {
                notificationEmail = decode(line.substring(2));
                continue;
            }
            String[] parts = line.split("\\|", 5);
            if (parts.length != 5) {
                continue;
            }
            try {
                int id = Integer.parseInt(parts[1]);
                boolean done = Boolean.parseBoolean(parts[2]);
                String dueDateTime = normalizeStoredDateTime(decode(parts[3]));
                String title = decode(parts[4]);
                Todo todo = new Todo(id, title, done, dueDateTime);
                if (parts[0].equals("T")) {
                    trash.add(todo);
                } else {
                    todos.add(todo);
                }
                nextId = Math.max(nextId, id + 1);
            } catch (RuntimeException e) {
                // 壊れた保存行は読み飛ばす
            }
        }
    }

    private static void saveData() throws IOException {
        StringBuilder data = new StringBuilder();
        data.append("E|").append(encode(notificationEmail)).append("\n");
        for (Todo todo : todos) {
            data.append(todo.line("A"));
        }
        for (Todo todo : trash) {
            data.append(todo.line("T"));
        }
        Files.writeString(DATA_FILE, data.toString(), StandardCharsets.UTF_8);
    }

    static String encode(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }
}

class Todo {
    private final int id;
    private String title;
    private boolean done;
    private String dueDate;

    Todo(int id, String title, boolean done, String dueDate) {
        this.id = id;
        this.title = title;
        this.done = done;
        this.dueDate = dueDate;
    }

    int getId() {
        return id;
    }

    String getTitle() {
        return title;
    }

    void setTitle(String title) {
        this.title = title;
    }

    boolean isDone() {
        return done;
    }

    void setDone(boolean done) {
        this.done = done;
    }

    String getDueDate() {
        return dueDate;
    }

    void setDueDate(String dueDate) {
        this.dueDate = dueDate;
    }

    String line(String location) {
        return location + "|" + id + "|" + done + "|" + App.encode(dueDate) + "|"
                + App.encode(title) + "\n";
    }
}
