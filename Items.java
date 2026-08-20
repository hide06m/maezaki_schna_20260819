import java.util.ArrayList;
import java.util.List;

public class Items {
    public static void main(String[] args) {
        List<Todo> todos = new ArrayList<>();
        todos.add(new Todo("Todoを作る", false));
        todos.add(new Todo("Todoを消す", true));
        todos.add(new Todo("Todoを直す", false));

        for (Todo todo : todos) {
            System.out.println(todo.toItem());
        }
    }
}

class Todo {
    String title;
    boolean done;

    Todo(String title, boolean done) {
        this.title = title;
        this.done = done;
    }

    String toItem() {
        if (done) {
            return "<li>[完了] " + title + "</li>";
        }
        return "<li>" + title + "</li>";
    }
}
