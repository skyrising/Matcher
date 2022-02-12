package matcher.gui.tab;

import com.github.javaparser.*;
import javafx.concurrent.Worker.State;
import javafx.scene.control.Tab;
import javafx.scene.web.WebView;
import matcher.NameType;
import matcher.config.Config;
import matcher.gui.Gui;
import matcher.gui.IGuiComponent;
import matcher.gui.ISelectionProvider;
import matcher.srcprocess.HtmlUtil;
import matcher.srcprocess.SrcDecorator;
import matcher.srcprocess.SrcDecorator.SrcParseException;
import matcher.type.ClassInstance;
import matcher.type.FieldInstance;
import matcher.type.MatchType;
import matcher.type.MethodInstance;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

public class SourcecodeTab extends Tab implements IGuiComponent {
	public SourcecodeTab(Gui gui, ISelectionProvider selectionProvider, boolean unmatchedTmp) {
		super("source");

		this.gui = gui;
		this.selectionProvider = selectionProvider;
		this.unmatchedTmp = unmatchedTmp;

		webView.getEngine().getLoadWorker().stateProperty().addListener((observable, oldValue, newValue) -> {
			if (newValue == State.SUCCEEDED) {
				Runnable r;

				while ((r = pendingWebViewTasks.poll()) != null) {
					r.run();
				}
			}
		});

		init();
	}

	private void init() {
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

		update(cls, true);
	}

	private void update(ClassInstance cls, boolean isRefresh) {
		pendingWebViewTasks.clear();

		final int cDecompId = ++decompId;

		if (cls == null) {
			displayText("no class selected");
			return;
		}

		if (!isRefresh) {
			displayText("decompiling...");
		}

		NameType nameType = gui.getNameType().withUnmatchedTmp(unmatchedTmp);

		//Gui.runAsyncTask(() -> gui.getEnv().decompile(cls, true))
		Gui.runAsyncTask(() -> SrcDecorator.decorate(gui.getEnv().decompile(gui.getDecompiler().get(), cls, nameType),
				cls, nameType))
		.whenComplete((res, exc) -> {
			if (cDecompId == decompId) {
				if (exc != null) {
					exc.printStackTrace();

					if (exc instanceof SrcParseException) {
						String source = ((SrcParseException) exc).source;
						String info;
						if (exc.getCause() instanceof ParseProblemException) {
							String[] lines = source.split("\n");
							List<Problem> problems = ((ParseProblemException) exc.getCause()).getProblems();
							info = problems.stream().flatMap(p -> {
								Optional<TokenRange> location = p.getLocation();
								Optional<Range> range = location.flatMap(l -> {
									Optional<Range> begin = l.getBegin().getRange();
									Optional<Range> end = l.getEnd().getRange();
									if (begin.isPresent() && end.isPresent()) {
										return Optional.of(begin.get().withEnd(end.get().end));
									}
									return Optional.empty();
								});
								List<String> messageLines = new ArrayList<>();
								messageLines.add(p.getVerboseMessage());
								if (range.isPresent()) {
									Position posStart = range.get().begin;
									Position posEnd = range.get().end;
									messageLines.addAll(Arrays.asList(lines).subList(posStart.line - 1, Math.min(posStart.line + 10, posEnd.line - 1)));
								}
								return messageLines.stream();
							}).collect(Collectors.joining("\n"));
						} else {
							info = exc.getCause().getMessage();
						}
						displayText(
							"parse error:\n" + info + "\ndecompiled source:\n" + source);
					} else {

						StringWriter sw = new StringWriter();
						exc.printStackTrace(new PrintWriter(sw));
						displayText("decompile error: " + sw);
					}
				} else {
					double prevScroll = isRefresh ? getScrollTop() : 0;

					displayHtml(res);

					if (isRefresh && prevScroll > 0) {
						addWebViewTask(() -> webView.getEngine().executeScript("document.body.scrollTop = "+prevScroll));
					}
				}
			} else if (exc != null) {
				exc.printStackTrace();
			}
		});
	}

	@Override
	public void onMethodSelect(MethodInstance method) {
		if (method != null) jumpTo(HtmlUtil.getId(method));
	}

	@Override
	public void onFieldSelect(FieldInstance field) {
		if (field != null) jumpTo(HtmlUtil.getId(field));
	}

	private void jumpTo(String anchorId) {
		if (unmatchedTmp) System.out.println("jump to "+anchorId);
		addWebViewTask(() -> {
			try {
				webView.getEngine().executeScript("selectElement('"+anchorId+"')");
			} catch (Exception e) {
				e.printStackTrace();
			}
		});
	}

	private void displayText(String text) {
		displayHtml(HtmlUtil.escape(text));
	}

	private void displayHtml(String html) {
		webView.getEngine().loadContent(TEMPLATE.replace("%theme%", Config.getDarkTheme() ? "dark" : "light").replace("%text%", html));
	}

	private double getScrollTop() {
		Object result;

		if (webView.getEngine().getLoadWorker().getState() == State.SUCCEEDED
				&& (result = webView.getEngine().executeScript("document.body.scrollTop")) instanceof Number) {
			return ((Number) result).doubleValue();
		} else {
			return 0;
		}
	}

	private void addWebViewTask(Runnable r) {
		if (webView.getEngine().getLoadWorker().getState() == State.SUCCEEDED) {
			r.run();
		} else {
			pendingWebViewTasks.add(r);
		}
	}

	private static String readTemplate(String name) {
		char[] buffer = new char[4000];
		int offset = 0;

		try (InputStream is = SourcecodeTab.class.getResourceAsStream("/"+name)) {
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

	private static final String TEMPLATE = readTemplate("ui/codeview/template.html");

	private final Gui gui;
	private final ISelectionProvider selectionProvider;
	private final boolean unmatchedTmp;
	//private final TextArea text = new TextArea();
	private final WebView webView = new WebView();
	private final Queue<Runnable> pendingWebViewTasks = new ArrayDeque<>();

	private int decompId;
}
