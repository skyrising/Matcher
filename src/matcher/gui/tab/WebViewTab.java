package matcher.gui.tab;

import javafx.concurrent.Worker;
import javafx.scene.control.Tab;
import javafx.scene.web.WebView;
import matcher.NameType;
import matcher.gui.Gui;
import matcher.gui.IGuiComponent;
import matcher.gui.ISelectionProvider;
import matcher.srcprocess.HtmlUtil;
import matcher.srcprocess.SrcDecorator;
import matcher.type.ClassInstance;
import matcher.type.FieldInstance;
import matcher.type.MatchType;
import matcher.type.MethodInstance;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Queue;
import java.util.Set;

public abstract class WebViewTab extends Tab implements IGuiComponent {
    public WebViewTab(String name, Gui gui, ISelectionProvider selectionProvider, boolean unmatchedTmp) {
        super(name);

        this.gui = gui;
        this.selectionProvider = selectionProvider;
        this.unmatchedTmp = unmatchedTmp;

        webView.getEngine().getLoadWorker().stateProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue == Worker.State.SUCCEEDED) {
                Runnable r;

                while ((r = pendingWebViewTasks.poll()) != null) {
                    r.run();
                }
            }
        });

        init();
    }

    protected void init() {
        displayText("no class selected");
        setContent(webView);
    }

    @Override
    public void onClassSelect(ClassInstance cls) {
        update(cls, false);
    }

    @Override
    public void onMatchChange(Set<MatchType> types) {
        ClassInstance cls = selectionProvider.getSelectedClass();

        if (cls != null) {
            update(cls, true);
        }
    }

    @Override
    public void onViewChange() {
        ClassInstance cls = selectionProvider.getSelectedClass();

        if (cls != null) {
            update(cls, true);
        }
    }

    protected abstract void update(ClassInstance cls, boolean isRefresh);

    @Override
    public void onMethodSelect(MethodInstance method) {
        if (method != null) jumpTo(HtmlUtil.getId(method));
    }

    @Override
    public void onFieldSelect(FieldInstance field) {
        if (field != null) jumpTo(HtmlUtil.getId(field));
    }

    protected void jumpTo(String anchorId) {
        if (unmatchedTmp) System.out.println("jump to "+anchorId);
        addWebViewTask(() -> webView.getEngine().executeScript(
                "var highlit = document.querySelectorAll('.highlight');" +
                "for (var i = 0; i < highlit.length; i++) highlit[i].classList.remove('highlight');" +
                "var anchorElem = document.getElementById('"+anchorId+"');" +
                "if (anchorElem !== null) {" +
                    "document.body.scrollTop = anchorElem.getBoundingClientRect().top + window.scrollY;" +
                    "anchorElem.classList.add('highlight');" +
                "}"));
    }

    protected void displayText(String text) {
        displayHtml(HtmlUtil.escape(text));
    }

    protected void displayHtml(String html) {
        webView.getEngine().loadContent(template.replace("%text%", html));
    }

    protected double getScrollTop() {
        Object result;

        if (webView.getEngine().getLoadWorker().getState() == Worker.State.SUCCEEDED
                && (result = webView.getEngine().executeScript("document.body.scrollTop")) instanceof Number) {
            return ((Number) result).doubleValue();
        } else {
            return 0;
        }
    }

    protected void addWebViewTask(Runnable r) {
        if (webView.getEngine().getLoadWorker().getState() == Worker.State.SUCCEEDED) {
            r.run();
        } else {
            pendingWebViewTasks.add(r);
        }
    }

    private static String readTemplate(String name) {
        char[] buffer = new char[4000];
        int offset = 0;

        try (InputStream is = SourcecodeTab.class.getClassLoader().getResourceAsStream(name)) {
            if (is == null) throw new FileNotFoundException(name);

            Reader reader = new InputStreamReader(is, StandardCharsets.UTF_8);
            int len;

            while ((len = reader.read(buffer, offset, buffer.length - offset)) != -1) {
                offset += len;

                if (offset == buffer.length) buffer = Arrays.copyOf(buffer, buffer.length * 2);
            }

            return new String(buffer, 0, offset);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static final String template = readTemplate("ui/SourceCodeTemplate.htm");

    protected final Gui gui;
    protected final ISelectionProvider selectionProvider;
    protected final boolean unmatchedTmp;
    protected final WebView webView = new WebView();
    protected final Queue<Runnable> pendingWebViewTasks = new ArrayDeque<>();
}
