package matcher.gui.tab;

import java.io.PrintWriter;
import java.io.StringWriter;

import javafx.scene.text.Font;
import matcher.srcprocess.HtmlUtil;
import matcher.type.FieldInstance;
import matcher.type.MethodInstance;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.util.Printer;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceClassVisitor;

import javafx.scene.control.Tab;
import javafx.scene.control.TextArea;
import matcher.gui.Gui;
import matcher.gui.IGuiComponent;
import matcher.gui.ISelectionProvider;
import matcher.type.ClassInstance;

public class BytecodeTab extends WebViewTab {
	public BytecodeTab(Gui gui, ISelectionProvider selectionProvider, boolean unmatchedTmp) {
		super("bytecode", gui, selectionProvider, unmatchedTmp);
	}

	@Override
	protected void update(ClassInstance cls, boolean isRefresh) {
		if (cls == null) {
			displayText("");
		} else {
			StringWriter writer = new StringWriter();

			try (PrintWriter pw = new PrintWriter(writer)) {
				cls.accept(new TraceClassVisitor(null, new BytecodePrinter(), pw), gui.getNameType().withUnmatchedTmp(unmatchedTmp));
			} catch (RuntimeException e) {
				e.printStackTrace();
				throw e;
			}

			String html = writer.toString();
			System.out.println(html);
			displayHtml(html);
		}
	}

	private static class BytecodePrinter extends Textifier {
		BytecodePrinter() {
			super(Opcodes.ASM7);
		}

		private void escapePrevious(int offset) {
		    int index = text.size() - offset;
            text.set(index, HtmlUtil.escape(String.valueOf(text.get(index))));
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
            super.visit(version, access, name, signature, superName, interfaces);
            escapePrevious(1);
        }

		@Override
		public Textifier visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
			text.add("<div id=\"method-" + HtmlUtil.escapeId(MethodInstance.getId(name, descriptor)) + "\">");
			Textifier t = super.visitMethod(access, name, descriptor, signature, exceptions);
            escapePrevious(2);
            return t;
		}

		@Override
		public void visitMethodEnd() {
			super.visitMethodEnd();
			text.add("</div>");
		}

		@Override
		public Textifier visitField(int access, String name, String descriptor, String signature, Object value) {
			text.add("<div id=\"field-" + HtmlUtil.escapeId(FieldInstance.getId(name, descriptor)) + "\">");
			Textifier t = super.visitField(access, name, descriptor, signature, value);
            escapePrevious(2);
            return t;
		}

		@Override
		public void visitFieldEnd() {
			super.visitFieldEnd();
			text.add("</div>");
		}

        @Override
        public void visitLdcInsn(Object value) {
            super.visitLdcInsn(value);
            escapePrevious(1);
        }

        @Override
        public void visitLocalVariable(String name, String descriptor, String signature, Label start, Label end, int index) {
            super.visitLocalVariable(name, descriptor, signature, start, end, index);
            escapePrevious(1);
        }

        @Override
        protected Textifier createTextifier() {
            return new BytecodePrinter();
        }
    }
}
