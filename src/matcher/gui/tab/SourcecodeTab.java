package matcher.gui.tab;

import java.io.PrintWriter;
import java.io.StringWriter;

import matcher.NameType;
import matcher.gui.Gui;
import matcher.gui.IGuiComponent;
import matcher.gui.ISelectionProvider;
import matcher.srcprocess.SrcDecorator;
import matcher.srcprocess.SrcDecorator.SrcParseException;
import matcher.type.ClassInstance;

public class SourcecodeTab extends WebViewTab implements IGuiComponent {
	public SourcecodeTab(Gui gui, ISelectionProvider selectionProvider, boolean unmatchedTmp) {
		super("source", gui, selectionProvider, unmatchedTmp);
	}

	@Override
	protected void update(ClassInstance cls, boolean isRefresh) {
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

					StringWriter sw = new StringWriter();
					exc.printStackTrace(new PrintWriter(sw));

					if (exc instanceof SrcParseException) {
						displayText("parse error: "+sw.toString()+"decompiled source:\n"+((SrcParseException) exc).source);
					} else {
						displayText("decompile error: "+sw.toString());
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

	private int decompId;
}
