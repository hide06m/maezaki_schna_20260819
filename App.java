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
import java.util.Comparator;
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
            todos.add(new Todo(nextId++, "牛乳を買う", false, "", "中", "買い物"));
            todos.add(new Todo(nextId++, "卵を買う", true, "", "高", "買い物"));
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
                addTodo(readBody(exchange));
                saveData();
                redirect(exchange);
                return;
            } else if (path.equals("/settings") && method.equals("POST")) {
                String email = formValue(readBody(exchange), "email");
                notificationEmail = isValidEmail(email) ? email : "";
                saveData();
                redirect(exchange);
                return;
            } else if (path.equals("/toggle")) {
                changeDone(exchange);
                saveData();
                redirect(exchange);
                return;
            } else if (path.equals("/bulk") && method.equals("POST")) {
                bulkAction(readBody(exchange));
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
                String query = exchange.getRequestURI().getRawQuery();
                message = homePage(formValue(query, "q"), validPriority(formValue(query, "priority")), formValue(query, "sort"), validTheme(formValue(query, "theme")));
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

    private static void addTodo(String formData) {
        String title = formValue(formData, "todo");
        String due = validDueDateTime(formValue(formData, "due"));
        String priority = validPriority(formValue(formData, "priority"));
        String category = validCategory(formValue(formData, "category"));
        if (isValidTitle(title) && due != null && priority != null && category != null) {
            todos.add(new Todo(nextId++, title, false, due, priority, category));
        }
    }

    private static String homePage(String search, String priorityFilter, String sort, String theme) {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head><meta charset='UTF-8'><title>わたしのTodo</title><style>")
                .append("body{max-width:760px;margin:24px auto;padding:0 16px;font-size:16px;")
                .append("font-family:'MS UI Gothic','ＭＳ Ｐゴシック',sans-serif;background:#008080;color:#000}")
                .append("main{background:#c0c0c0;border:3px outset #eee;padding:14px}")
                .append("h1{color:#fff;background:#000080;padding:8px;font-size:24px;margin:0 0 14px}")
                .append("input[type=text],input[type=email],input[type=datetime-local]{width:220px}")
                .append("select{min-width:90px}a{color:#000080}li{margin:8px 0}.todo-done{text-decoration:line-through;color:#888}.bulk-select{display:inline-block}.bulk-controls{display:block}")
                .append("")
                .append("")
                .append(".notice{background:#ffffcc;border:1px dashed #000;padding:6px}")
                .append(themeCss(theme))
                .append("</style></head><body><main>")
                .append("<h1>★ わたしのTodo ★</h1>")
                .append("<p class='notice'>ようこそ！ 今日もこつこつ片づけよう！</p>")
                .append("<form method='post' action='/add'>")
                .append("<input type='text' name='todo' maxlength='200' placeholder='やることを入力' required>")
                .append("<input type='datetime-local' name='due'>")
                .append(prioritySelect("中", "priority"))
                .append("<input type='text' name='category' maxlength='50' placeholder='カテゴリ'>")
                .append("<button type='submit'>追加</button></form>")
                .append("<form method='get' action='/' class='theme-form'>")
                .append("テーマ: ").append(themeSelect(theme))
                .append("</form>")
                .append("<h2>検索・絞り込み</h2>")
                .append("<form method='get' action='/'>")
                .append("<input type='text' name='q' value='").append(htmlEscape(search))
                .append("' placeholder='タイトル・カテゴリを検索'>")
                .append(priorityFilterSelect(priorityFilter))
                .append(sortSelect(sort))
                .append("<button type='submit'>検索</button> <a href='/'>すべて表示</a></form>")
                .append("<h2>タスク一覧</h2><div id='todoList'>")
                .append("<form id='bulkForm' method='post' action='/bulk'></form><ul>");

        List<Todo> visibleTodos = new ArrayList<>();
        for (Todo todo : todos) {
            if (matches(todo, search, priorityFilter)) {
                visibleTodos.add(todo);
            }
        }
        visibleTodos.sort((left, right) -> compareTodos(left, right, sort));
        if (visibleTodos.isEmpty()) {
            html.append(todos.isEmpty() ? "</ul><p>タスクはありません</p>"
                    : "</ul><p>条件に一致するTodoはありません</p>");
        } else {
            for (Todo todo : visibleTodos) {
                html.append(todoHtml(todo));
            }
            html.append("</ul>");
        }

        html.append("<div class='bulk-controls'><p><select name='action' form='bulkForm'><option value='done'>選択したTodoを完了</option><option value='delete'>選択したTodoをゴミ箱へ</option></select> <button type='submit' form='bulkForm'>一括実行</button></p></div></div>")
                .append("<h2>通知設定</h2><form method='post' action='/settings'>")
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
                .append("<h2>ゴミ箱</h2><button type='button' onclick='toggleTrash()'>表示 / 非表示</button>")
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
                .append("function toggleBulkMode(){document.getElementById('todoList').classList.toggle('bulk-mode');}")
                .append("function toggleTrash(){var x=document.getElementById('trash');x.style.display=x.style.display==='none'?'block':'none';}")
                .append("function enableBrowserNotification(){if('Notification' in window){Notification.requestPermission().then(checkNotifications);}")
                .append("else{alert('このブラウザは通知に対応していません');}}")
                .append("function testBrowserNotification(){if('Notification' in window&&Notification.permission==='granted'){")
                .append("new Notification('Todo通知',{body:'通知のテストです'});}else{alert('先にブラウザ通知を許可してください');}}")
                .append("function checkNotifications(){if(!('Notification' in window)||Notification.permission!=='granted')return;")
                .append("fetch('/notifications').then(function(r){return r.json();}).then(function(items){")
                .append("var sent=JSON.parse(localStorage.getItem('todoNotifications')||'{}');")
                .append("items.forEach(function(item){if(!sent[item.id]){new Notification('Todoの期限',{body:item.title});sent[item.id]=true;}});")
                .append("localStorage.setItem('todoNotifications',JSON.stringify(sent));});}")
                .append("setInterval(checkNotifications,60000);checkNotifications();")
                .append("</script></main></body></html>");
        return html.toString();
    }

    private static String todoHtml(Todo todo) {
        String due = todo.getDueDate().isEmpty() ? "" : "（期限: "
                + htmlEscape(todo.getDueDate().replace("T", " ")) + "）";
        String titleClass = todo.isDone() ? "todo-title todo-done" : "todo-title";
        return "<li class='todo-card'>"
                + "<input type='checkbox' class='bulk-select' form='bulkForm' name='selected' value='" + todo.getId() + "'> "
                + "<a class='" + titleClass + "' href='/toggle?id=" + todo.getId() + "'>"
                + htmlEscape(todo.getTitle()) + "</a>"
                + "<span class='todo-info'>[" + htmlEscape(todo.getPriority()) + "]"
                + (todo.getCategory().isEmpty() ? "" : " / " + htmlEscape(todo.getCategory()))
                + due + "</span><span class='todo-actions'> "
                + "<a href='/edit?id=" + todo.getId() + "'>編集</a>"
                + " <a href='/delete?id=" + todo.getId() + "'>削除</a></span></li>";
    }
    private static String editPage(Integer id) {
        Todo target = findById(todos, id);
        if (target == null) {
            return "<!DOCTYPE html><html><head><meta charset='UTF-8'><title>Todo編集</title></head><body><main><p>Todoが見つかりません</p><a href='/'>戻る</a></main></body></html>";
        }
        return "<!DOCTYPE html><html><head><meta charset='UTF-8'><title>Todo編集</title><style>"
                + "body{max-width:760px;margin:24px auto;padding:0 16px;font-size:16px;font-family:'MS UI Gothic','ＭＳ Ｐゴシック',sans-serif;background:#008080;color:#000}"
                + "main{background:#c0c0c0;border:3px outset #eee;padding:14px}"
                + "h1{color:#fff;background:#000080;padding:8px;font-size:24px;margin:0 0 14px}"
                + "input[type=text],input[type=datetime-local]{width:280px}label{display:block;margin:10px 0}a{color:#000080}"
                + "</style></head><body><main><h1>Todoを編集</h1>"
                + "<form method='post' action='/edit'>"
                + "<input type='hidden' name='id' value='" + target.getId() + "'>"
                + "<label>やること<br><input type='text' name='todo' maxlength='200' value='"
                + htmlEscape(target.getTitle()) + "' required></label>"
                + "<label>期限<br><input type='datetime-local' name='due' value='"
                + htmlEscape(target.getDueDate()) + "'></label>"
                + prioritySelect(target.getPriority(), "priority")
                + "<label>カテゴリ<br><input type='text' name='category' maxlength='50' value='"
                + htmlEscape(target.getCategory()) + "'></label>"
                + "<button type='submit'>保存</button> <a href='/'>キャンセル</a></form>"
                + "</main></body></html>";
    }
    private static String prioritySelect(String selected, String name) {
        StringBuilder html = new StringBuilder("<select name='" + name + "'>");
        for (String option : new String[]{"高", "中", "低"}) {
            html.append("<option value='").append(option).append("'")
                    .append(option.equals(selected) ? " selected" : "").append(">")
                    .append(option).append("</option>");
        }
        return html.append("</select>").toString();
    }

    private static String priorityFilterSelect(String selected) {
        StringBuilder html = new StringBuilder("<select name='priority'>");
        html.append("<option value=''").append(selected == null || selected.isEmpty() ? " selected" : "")
                .append(">すべて</option>");
        for (String option : new String[]{"高", "中", "低"}) {
            html.append("<option value='").append(option).append("'")
                    .append(option.equals(selected) ? " selected" : "").append(">").append(option).append("</option>");
        }
        return html.append("</select>").toString();
    }
    private static String sortSelect(String selected) {
        StringBuilder html = new StringBuilder("<select name='sort'>");
        String[][] options = {{"priority", "優先度順"}, {"due", "期限順"}, {"new", "新しい順"}};
        for (String[] option : options) {
            html.append("<option value='").append(option[0]).append("'")
                    .append(option[0].equals(selected) ? " selected" : "").append(">").append(option[1]).append("</option>");
        }
        return html.append("</select>").toString();
    }

    private static int compareTodos(Todo left, Todo right, String sort) {
        if ("due".equals(sort)) {
            String leftDue = left.getDueDate();
            String rightDue = right.getDueDate();
            if (leftDue.isEmpty() && rightDue.isEmpty()) return 0;
            if (leftDue.isEmpty()) return 1;
            if (rightDue.isEmpty()) return -1;
            return leftDue.compareTo(rightDue);
        }
        if ("new".equals(sort)) {
            return Integer.compare(right.getId(), left.getId());
        }
        return Integer.compare(priorityNumber(left.getPriority()), priorityNumber(right.getPriority()));
    }

    private static int priorityNumber(String priority) {
        if ("高".equals(priority)) return 0;
        if ("低".equals(priority)) return 2;
        return 1;
    }
    private static String themeSelect(String selected) {
        StringBuilder html = new StringBuilder("<select name='theme' onchange='this.form.submit()' title='テーマを選ぶ'>");
        String[][] options = {{"classic", "通常"}, {"win98", "Windows 98風"}, {"xp", "Windows XP風"}, {"2ch", "2ちゃんねる風"}, {"vipper", "Vipper風"}};
        for (String[] option : options) {
            html.append("<option value='").append(option[0]).append("'")
                    .append(option[0].equals(selected) ? " selected" : "")
                    .append(">").append(option[1]).append("</option>");
        }
        return html.append("</select>").toString();
    }

    private static String validTheme(String value) {
        if (value == null) return "classic";
        if (value.equals("win98") || value.equals("xp") || value.equals("2ch") || value.equals("vipper")) {
            return value;
        }
        return "classic";
    }

    private static String themeCss(String theme) {
        if (theme.equals("win98")) {
            return "body{background:#008080;color:#000}main{background:#c0c0c0;border:3px outset #fff;border-radius:0;box-shadow:none}" +
                    "h1{color:#fff;background:#000080;padding:8px;font-family:'MS UI Gothic',sans-serif}.todo-card{border:2px outset #fff;border-radius:0;background:#d4d0c8}";
        }
        if (theme.equals("xp")) {
            return "body{background:linear-gradient(#dceeff,#7db7ef);color:#17365d}main{background:#f4f8ff;border:2px solid #3976b8;border-radius:12px;box-shadow:0 3px 12px #477}" +
                    "h1{color:#fff;background:linear-gradient(#4b9bea,#0755a0);border-radius:8px;padding:10px}.todo-card{border:1px solid #9cc5eb;border-radius:8px;background:#fff}";
        }
        if (theme.equals("2ch")) {
            return "body{background:#eee;color:#222;font-family:monospace}main{background:#fff;border:1px solid #999;border-radius:0;box-shadow:none}" +
                    "h1{color:#000;background:#eee;border-bottom:3px solid #888;padding:8px;font-size:22px}.todo-card{border:1px solid #ccc;border-radius:0;background:#fafafa}";
        }
        if (theme.equals("vipper")) {
            return "body{background:#fff4d6;color:#4b2500;font-family:monospace}main{background:#fffaf0;border:3px double #e08000;border-radius:4px;box-shadow:none}" +
                    "h1{color:#fff;background:#e08000;padding:8px}.todo-card{border:1px dashed #e08000;border-radius:4px;background:#fff}";
        }
        return "";
    }
    private static boolean matches(Todo todo, String search, String priorityFilter) {
        boolean textMatches = search == null || search.isEmpty()
                || todo.getTitle().toLowerCase().contains(search.toLowerCase())
                || todo.getCategory().toLowerCase().contains(search.toLowerCase());
        boolean priorityMatches = priorityFilter == null || priorityFilter.isEmpty()
                || todo.getPriority().equals(priorityFilter);
        return textMatches && priorityMatches;
    }

    private static void editTodo(String formData) {
        Todo target = findById(todos, parseId(formValue(formData, "id")));
        String title = formValue(formData, "todo");
        String due = validDueDateTime(formValue(formData, "due"));
        String priority = validPriority(formValue(formData, "priority"));
        String category = validCategory(formValue(formData, "category"));
        if (target != null && isValidTitle(title) && due != null && priority != null && category != null) {
            target.setTitle(title);
            target.setDueDate(due);
            target.setPriority(priority);
            target.setCategory(category);
        }
    }

    private static void bulkAction(String formData) {
        String action = formValue(formData, "action");
        for (String value : formValues(formData, "selected")) {
            Integer id = parseId(value);
            Todo todo = findById(todos, id);
            if (todo == null) continue;
            if (action.equals("done")) {
                todo.setDone(true);
            } else if (action.equals("delete")) {
                todos.remove(todo);
                trash.add(todo);
            }
        }
    }

    private static List<String> formValues(String formData, String key) {
        List<String> values = new ArrayList<>();
        if (formData == null) return values;
        for (String part : formData.split("&")) {
            String[] pair = part.split("=", 2);
            if (pair.length == 2 && pair[0].equals(key)) {
                values.add(URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
            }
        }
        return values;
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
        if (id == null) return null;
        for (Todo todo : list) if (todo.getId() == id) return todo;
        return null;
    }

    private static Integer readId(HttpExchange exchange) {
        return parseId(formValue(exchange.getRequestURI().getRawQuery(), "id"));
    }

    private static Integer parseId(String value) {
        if (value == null || value.isEmpty()) return null;
        try { return Integer.parseInt(value); } catch (NumberFormatException e) { return null; }
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static String formValue(String formData, String key) {
        if (formData == null) return "";
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
        if (value == null || value.isEmpty()) return "";
        try { LocalDateTime.parse(value); return value; }
        catch (DateTimeParseException e) { return null; }
    }

    private static String normalizeStoredDateTime(String value) {
        if (value == null || value.isEmpty()) return "";
        try {
            if (value.length() == 10) return LocalDate.parse(value).atTime(23, 59).toString();
            return LocalDateTime.parse(value).toString();
        } catch (DateTimeParseException e) { return ""; }
    }

    private static String validPriority(String value) {
        return value != null && (value.equals("高") || value.equals("中") || value.equals("低")) ? value : null;
    }

    private static String validCategory(String value) {
        return value != null && value.length() <= 50 ? value.trim() : null;
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

    private static String dueNotificationsJson() {
        StringBuilder json = new StringBuilder("[");
        LocalDateTime now = LocalDateTime.now();
        boolean first = true;
        for (Todo todo : todos) {
            if (!todo.isDone() && !todo.getDueDate().isEmpty()) {
                try {
                    if (!LocalDateTime.parse(todo.getDueDate()).isAfter(now)) {
                        if (!first) json.append(",");
                        json.append("{\"id\":").append(todo.getId())
                                .append(",\"title\":\"").append(jsonEscape(todo.getTitle())).append("\"}");
                        first = false;
                    }
                } catch (DateTimeParseException e) {
                    // 不正な期限は通知しない
                }
            }
        }
        return json.append("]").toString();
    }

    private static void redirect(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Location", "/");
        exchange.sendResponseHeaders(303, -1);
        exchange.getResponseBody().close();
    }

    private static void loadData() throws IOException {
        if (!Files.exists(DATA_FILE)) return;
        todos.clear();
        trash.clear();
        for (String line : Files.readAllLines(DATA_FILE, StandardCharsets.UTF_8)) {
            if (line.startsWith("E|")) {
                notificationEmail = decode(line.substring(2));
                continue;
            }
            String[] parts = line.split("\\|", 7);
            if (parts.length != 5 && parts.length != 7) continue;
            try {
                int id = Integer.parseInt(parts[1]);
                boolean done = Boolean.parseBoolean(parts[2]);
                String due = normalizeStoredDateTime(decode(parts[3]));
                String title = decode(parts[4]);
                String priority = parts.length == 7 ? validPriority(decode(parts[5])) : "中";
                String category = parts.length == 7 ? validCategory(decode(parts[6])) : "";
                if (priority == null || category == null) {
                    priority = "中";
                    category = "";
                }
                Todo todo = new Todo(id, title, done, due, priority, category);
                if (parts[0].equals("T")) trash.add(todo); else todos.add(todo);
                nextId = Math.max(nextId, id + 1);
            } catch (RuntimeException e) {
                // 壊れた保存行は読み飛ばす
            }
        }
    }

    private static void saveData() throws IOException {
        StringBuilder data = new StringBuilder("E|").append(encode(notificationEmail)).append("\n");
        for (Todo todo : todos) data.append(todo.line("A"));
        for (Todo todo : trash) data.append(todo.line("T"));
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
    private String priority;
    private String category;

    Todo(int id, String title, boolean done, String dueDate, String priority, String category) {
        this.id = id;
        this.title = title;
        this.done = done;
        this.dueDate = dueDate;
        this.priority = priority;
        this.category = category;
    }

    int getId() { return id; }
    String getTitle() { return title; }
    void setTitle(String title) { this.title = title; }
    boolean isDone() { return done; }
    void setDone(boolean done) { this.done = done; }
    String getDueDate() { return dueDate; }
    void setDueDate(String dueDate) { this.dueDate = dueDate; }
    String getPriority() { return priority; }
    void setPriority(String priority) { this.priority = priority; }
    String getCategory() { return category; }
    void setCategory(String category) { this.category = category; }

    String line(String location) {
        return location + "|" + id + "|" + done + "|" + App.encode(dueDate) + "|"
                + App.encode(title) + "|" + App.encode(priority) + "|" + App.encode(category) + "\n";
    }
}
