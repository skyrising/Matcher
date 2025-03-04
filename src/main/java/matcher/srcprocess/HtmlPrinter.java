package matcher.srcprocess;

import com.github.javaparser.ast.*;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.comments.BlockComment;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.comments.JavadocComment;
import com.github.javaparser.ast.comments.LineComment;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.modules.*;
import com.github.javaparser.ast.nodeTypes.NodeWithName;
import com.github.javaparser.ast.nodeTypes.NodeWithTraversableScope;
import com.github.javaparser.ast.nodeTypes.NodeWithTypeArguments;
import com.github.javaparser.ast.nodeTypes.NodeWithVariables;
import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.ast.type.*;
import com.github.javaparser.ast.visitor.Visitable;
import com.github.javaparser.ast.visitor.VoidVisitor;
import com.github.javaparser.printer.PrettyPrintVisitor;
import com.github.javaparser.printer.PrettyPrinterConfiguration;
import com.github.javaparser.printer.PrettyPrinterConfiguration.IndentType;
import com.github.javaparser.printer.SourcePrinter;
import com.github.javaparser.utils.Utils;
import matcher.type.FieldInstance;
import matcher.type.MethodInstance;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.github.javaparser.ast.Node.Parsedness.UNPARSABLE;
import static com.github.javaparser.utils.PositionUtils.sortByBeginPosition;
import static com.github.javaparser.utils.Utils.isNullOrEmpty;

public class HtmlPrinter implements VoidVisitor<Void> {
	public HtmlPrinter(TypeResolver typeResolver) {
		this.typeResolver = typeResolver;
		configuration = new PrettyPrinterConfiguration().setIndentSize(1).setIndentType(IndentType.TABS).setEndOfLineCharacter("\n");
		printer = (new PrettyPrintVisitor(configuration) {
			SourcePrinter getPrinter() {
				return printer;
			}
		}).getPrinter();
	}

	public String getSource() {
		return printer.getSource();
	}

	private void printModifiers(final EnumSet<Modifier> modifiers) {
		for (Modifier m : modifiers) {
			printer.print("<span class=\"keyword\">");
			printer.print(m.asString());
			printer.print("</span> ");
		}
	}

	private void printMembers(final NodeList<BodyDeclaration<?>> members, final Void arg) {
		List<BodyDeclaration<?>> sortedMembers = new ArrayList<>(members);
		sortedMembers.sort(Comparator
				.comparingInt(HtmlPrinter::getTypeIdx)
				.thenComparing((a, b) -> {
					// move instance methods after constructors
					if (a.isConstructorDeclaration() && b.isMethodDeclaration() && !((MethodDeclaration) b).getModifiers().contains(Modifier.STATIC)) {
						return -1;
					} else if (b.isConstructorDeclaration() && a.isMethodDeclaration() && !((MethodDeclaration) a).getModifiers().contains(Modifier.STATIC)) {
						return 1;
					} else {
						return 0;
					}
				}));

		BodyDeclaration<?> prev = null;

		for (final BodyDeclaration<?> member : sortedMembers) {
			if (prev != null &&
					(!prev.isFieldDeclaration()
							|| !member.isFieldDeclaration()
							|| ((FieldDeclaration) prev).getModifiers().contains(Modifier.STATIC) && !((FieldDeclaration) member).getModifiers().contains(Modifier.STATIC))) {
				printer.println();
			}

			member.accept(this, arg);
			printer.println();

			prev = member;
		}
	}

	private static int getTypeIdx(BodyDeclaration<?> decl) {
		// order: enum-cst static-init/static-field inst-init/inst-field mth/ctor/annotation inst-type static-type
		// note: static-init <-> fields and inst-init <-> inst-field can't be reordered with each other as their order determines execution order

		if (decl.isEnumConstantDeclaration()) {
			return 0;
		} else if (decl.isAnnotationMemberDeclaration() || decl.isCallableDeclaration()) {
			return 3;
		} else if (decl.isFieldDeclaration()) {
			return ((FieldDeclaration) decl).getModifiers().contains(Modifier.STATIC) ? 1 : 2;
		} else if (decl.isInitializerDeclaration()) {
			return ((InitializerDeclaration) decl).isStatic() ? 1 : 2;
		} else if (decl.isTypeDeclaration()) {
			if (decl.isClassOrInterfaceDeclaration() && ((ClassOrInterfaceDeclaration) decl).isInterface() || ((TypeDeclaration<?>) decl).isStatic()) {
				return 5;
			} else {
				return 4;
			}
		} else {
			throw new RuntimeException("unknown body decl type: "+decl.getClass().getName());
		}
	}

	private void printMemberAnnotations(final NodeList<AnnotationExpr> annotations, final Void arg) {
		if (annotations.isEmpty()) {
			return;
		}
		for (final AnnotationExpr a : annotations) {
			a.accept(this, arg);
			printer.println();
		}
	}

	private void printAnnotations(final NodeList<AnnotationExpr> annotations, boolean prefixWithASpace,
			final Void arg) {
		if (annotations.isEmpty()) {
			return;
		}
		if (prefixWithASpace) {
			printer.print(" ");
		}
		for (AnnotationExpr annotation : annotations) {
			annotation.accept(this, arg);
			printer.print(" ");
		}
	}

	private void printTypeArgs(final NodeWithTypeArguments<?> nodeWithTypeArguments, final Void arg) {
		NodeList<Type> typeArguments = nodeWithTypeArguments.getTypeArguments().orElse(null);
		if (!isNullOrEmpty(typeArguments)) {
			printer.print("&lt;");
			for (final Iterator<Type> i = typeArguments.iterator(); i.hasNext(); ) {
				final Type t = i.next();
				t.accept(this, arg);
				if (i.hasNext()) {
					printer.print(", ");
				}
			}
			printer.print("&gt;");
		}
	}

	private void printTypeParameters(final NodeList<TypeParameter> args, final Void arg) {
		if (!isNullOrEmpty(args)) {
			printer.print("&lt;");
			for (final Iterator<TypeParameter> i = args.iterator(); i.hasNext(); ) {
				final TypeParameter t = i.next();
				t.accept(this, arg);
				if (i.hasNext()) {
					printer.print(", ");
				}
			}
			printer.print("&gt;");
		}
	}

	private void printArguments(final NodeList<Expression> args, final Void arg) {
		printer.print("(");
		if (!isNullOrEmpty(args)) {
			boolean columnAlignParameters = (args.size() > 1) && configuration.isColumnAlignParameters();
			if (columnAlignParameters) {
				printer.indentWithAlignTo(printer.getCursor().column);
			}
			for (final Iterator<Expression> i = args.iterator(); i.hasNext(); ) {
				final Expression e = i.next();
				e.accept(this, arg);
				if (i.hasNext()) {
					printer.print(",");
					if (columnAlignParameters) {
						printer.println();
					} else {
						printer.print(" ");
					}
				}
			}
			if (columnAlignParameters) {
				printer.unindent();
			}
		}
		printer.print(")");
	}

	private void printPrePostFixOptionalList(final NodeList<? extends Visitable> args, final Void arg, String prefix, String separator, String postfix) {
		if (!args.isEmpty()) {
			printer.print(prefix);
			for (final Iterator<? extends Visitable> i = args.iterator(); i.hasNext(); ) {
				final Visitable v = i.next();
				v.accept(this, arg);
				if (i.hasNext()) {
					printer.print(separator);
				}
			}
			printer.print(postfix);
		}
	}

	private void printPrePostFixRequiredList(final NodeList<? extends Visitable> args, final Void arg, String prefix, String separator, String postfix) {
		printer.print(prefix);
		if (!args.isEmpty()) {
			for (final Iterator<? extends Visitable> i = args.iterator(); i.hasNext(); ) {
				final Visitable v = i.next();
				v.accept(this, arg);
				if (i.hasNext()) {
					printer.print(separator);
				}
			}
		}
		printer.print(postfix);
	}

	private void printComment(final Optional<Comment> comment, final Void arg) {
		comment.ifPresent(c -> c.accept(this, arg));
	}

	@Override
	public void visit(final CompilationUnit n, final Void arg) {
		printComment(n.getComment(), arg);
		if (n.getParsed() == UNPARSABLE) {
			printer.println("???");
			return;
		}

		if (n.getPackageDeclaration().isPresent()) {
			n.getPackageDeclaration().get().accept(this, arg);
		}

		n.getImports().accept(this, arg);
		if (!n.getImports().isEmpty()) {
			printer.println();
		}

		for (final Iterator<TypeDeclaration<?>> i = n.getTypes().iterator(); i.hasNext(); ) {
			i.next().accept(this, arg);
			printer.println();
			if (i.hasNext()) {
				printer.println();
			}
		}

		n.getModule().ifPresent(m -> m.accept(this, arg));

		printOrphanCommentsEnding(n);
	}

	@Override
	public void visit(final PackageDeclaration n, final Void arg) {
		printComment(n.getComment(), arg);
		printMemberAnnotations(n.getAnnotations(), arg);
		printer.print("<span class=\"keyword\">package</span> ");
		n.getName().accept(this, arg);
		printer.println(";");
		printer.println();

		printOrphanCommentsEnding(n);
	}

	@Override
	public void visit(final NameExpr n, final Void arg) {
		printComment(n.getComment(), arg);
		n.getName().accept(this, arg);

		printOrphanCommentsEnding(n);
	}

	@Override
	public void visit(final Name n, final Void arg) {
		printComment(n.getComment(), arg);
		if (n.getQualifier().isPresent()) {
			n.getQualifier().get().accept(this, arg);
			printer.print(".");
		}
		printAnnotations(n.getAnnotations(), false, arg);
		printer.print(n.getIdentifier());

		printOrphanCommentsEnding(n);
	}

	@Override
	public void visit(SimpleName n, Void arg) {
		printer.print(HtmlUtil.escape(n.getIdentifier()));
	}

	@Override
	public void visit(final ClassOrInterfaceDeclaration n, final Void arg) {
		printComment(n.getComment(), arg);
		printMemberAnnotations(n.getAnnotations(), arg);
		printModifiers(n.getModifiers());

		if (n.isInterface()) {
			printer.print("<span class=\"keyword\">interface</span> ");
		} else {
			printer.print("<span class=\"keyword\">class</span> ");
		}

		n.getName().accept(this, arg);

		printTypeParameters(n.getTypeParameters(), arg);

		if (!n.getExtendedTypes().isEmpty()) {
			printer.print(" <span class=\"keyword\">extends</span> ");
			for (final Iterator<ClassOrInterfaceType> i = n.getExtendedTypes().iterator(); i.hasNext(); ) {
				final ClassOrInterfaceType c = i.next();
				c.accept(this, arg);
				if (i.hasNext()) {
					printer.print(", ");
				}
			}
		}

		if (!n.getImplementedTypes().isEmpty()) {
			printer.print(" <span class=\"keyword\">implements</span> ");
			for (final Iterator<ClassOrInterfaceType> i = n.getImplementedTypes().iterator(); i.hasNext(); ) {
				final ClassOrInterfaceType c = i.next();
				c.accept(this, arg);
				if (i.hasNext()) {
					printer.print(", ");
				}
			}
		}

		printer.println(" {");
		printer.indent();
		if (!isNullOrEmpty(n.getMembers())) {
			printMembers(n.getMembers(), arg);
		}

		printOrphanCommentsEnding(n);

		printer.unindent();
		printer.print("}");
	}

	@Override
	public void visit(final JavadocComment n, final Void arg) {
		if (configuration.isPrintComments() && configuration.isPrintJavadoc()) {
			printer.println("<span class=\"javadoc\">/**");
			final String commentContent = Utils.normalizeEolInTextBlock(HtmlUtil.escape(n.getContent()), configuration.getEndOfLineCharacter());
			String[] lines = commentContent.split("\\R");
			boolean skippingLeadingEmptyLines = true;
			boolean prependEmptyLine = false;
			boolean prependSpace = Arrays.stream(lines).anyMatch(line -> !line.isEmpty() && !line.startsWith(" "));
			for (String line : lines) {
				final String trimmedLine = line.trim();
				if (trimmedLine.startsWith("*")) {
					line = trimmedLine.substring(1);
				}
				line = Utils.trimTrailingSpaces(line);
				if (line.isEmpty()) {
					if (!skippingLeadingEmptyLines) {
						prependEmptyLine = true;
					}
				} else {
					skippingLeadingEmptyLines = false;
					if (prependEmptyLine) {
						printer.println(" *");
						prependEmptyLine = false;
					}
					printer.print(" *");
					if (prependSpace) {
						printer.print(" ");
					}
					printer.println(line);
				}
			}
			printer.println(" */</span>");
		}
	}

	@Override
	public void visit(final ClassOrInterfaceType n, final Void arg) {
		printComment(n.getComment(), arg);
		if (n.getScope().isPresent()) {
			n.getScope().get().accept(this, arg);
			printer.print(".");
		}
		printAnnotations(n.getAnnotations(), false, arg);

		n.getName().accept(this, arg);

		if (n.isUsingDiamondOperator()) {
			printer.print("<>");
		} else {
			printTypeArgs(n, arg);
		}
	}

	@Override
	public void visit(final TypeParameter n, final Void arg) {
		printComment(n.getComment(), arg);
		printAnnotations(n.getAnnotations(), false, arg);
		n.getName().accept(this, arg);
		if (!isNullOrEmpty(n.getTypeBound())) {
			printer.print(" <span class=\"keyword\">extends</span> ");
			for (final Iterator<ClassOrInterfaceType> i = n.getTypeBound().iterator(); i.hasNext(); ) {
				final ClassOrInterfaceType c = i.next();
				c.accept(this, arg);
				if (i.hasNext()) {
					printer.print(" & ");
				}
			}
		}
	}

	@Override
	public void visit(final PrimitiveType n, final Void arg) {
		printComment(n.getComment(), arg);
		printAnnotations(n.getAnnotations(), true, arg);
		printer.print("<span class=\"keyword\">");
		printer.print(n.getType().asString());
		printer.print("</span>");
	}

	@Override
	public void visit(final ArrayType n, final Void arg) {
		final List<ArrayType> arrayTypeBuffer = new LinkedList<>();
		Type type = n;
		while (type instanceof ArrayType) {
			final ArrayType arrayType = (ArrayType) type;
			arrayTypeBuffer.add(arrayType);
			type = arrayType.getComponentType();
		}

		type.accept(this, arg);
		for (ArrayType arrayType : arrayTypeBuffer) {
			printAnnotations(arrayType.getAnnotations(), true, arg);
			printer.print("[]");
		}
	}

	@Override
	public void visit(final ArrayCreationLevel n, final Void arg) {
		printAnnotations(n.getAnnotations(), true, arg);
		printer.print("[");
		if (n.getDimension().isPresent()) {
			n.getDimension().get().accept(this, arg);
		}
		printer.print("]");
	}

	@Override
	public void visit(final IntersectionType n, final Void arg) {
		printComment(n.getComment(), arg);
		printAnnotations(n.getAnnotations(), false, arg);
		boolean isFirst = true;
		for (ReferenceType element : n.getElements()) {
			if (isFirst) {
				isFirst = false;
			} else {
				printer.print(" & ");
			}
			element.accept(this, arg);
		}
	}

	@Override
	public void visit(final UnionType n, final Void arg) {
		printComment(n.getComment(), arg);
		printAnnotations(n.getAnnotations(), true, arg);
		boolean isFirst = true;
		for (ReferenceType element : n.getElements()) {
			if (isFirst) {
				isFirst = false;
			} else {
				printer.print(" | ");
			}
			element.accept(this, arg);
		}
	}

	@Override
	public void visit(final WildcardType n, final Void arg) {
		printComment(n.getComment(), arg);
		printAnnotations(n.getAnnotations(), false, arg);
		printer.print("?");
		if (n.getExtendedType().isPresent()) {
			printer.print(" <span class=\"keyword\">extends</span> ");
			n.getExtendedType().get().accept(this, arg);
		}
		if (n.getSuperType().isPresent()) {
			printer.print(" <span class=\"keyword\">super</span> ");
			n.getSuperType().get().accept(this, arg);
		}
	}

	@Override
	public void visit(final UnknownType n, final Void arg) {
		// Nothing to print
	}

	@Override
	public void visit(final FieldDeclaration n, final Void arg) {
		printOrphanCommentsBeforeThisChildNode(n);

		boolean singleVar = n.getVariables().size() == 1;
		boolean hasSingleField = singleVar && fieldStart(n.getVariable(0));

		printComment(n.getComment(), arg);
		printMemberAnnotations(n.getAnnotations(), arg);
		printModifiers(n.getModifiers());
		if (!n.getVariables().isEmpty()) {
			Optional<Type> maximumCommonType = n.getMaximumCommonType();
			maximumCommonType.ifPresent(t -> t.accept(this, arg));
			if (!maximumCommonType.isPresent()) {
				printer.print("???");
			}
		}

		printer.print(" ");
		for (final Iterator<VariableDeclarator> i = n.getVariables().iterator(); i.hasNext(); ) {
			final VariableDeclarator var = i.next();

			boolean hasField = !singleVar && fieldStart(var);

			var.accept(this, arg);

			if (hasField) printer.print("</span>");

			if (i.hasNext()) {
				printer.print(", ");
			}
		}

		printer.print(";");

		if (hasSingleField) printer.print("</span>");
	}

	private boolean fieldStart(VariableDeclarator var) {
		FieldInstance field = typeResolver.getField(var);

		if (field != null) {
			printer.unindent();
			printer.print("<span id=\"");
			printer.print(HtmlUtil.getId(field));
			printer.print("\">\t");
			printer.indent();

			return true;
		} else {
			return false;
		}
	}

	@Override
	public void visit(final VariableDeclarator n, final Void arg) {
		printComment(n.getComment(), arg);

		boolean isField = n.getParentNode().orElse(null) instanceof FieldDeclaration;

		printer.print("<span class=\"");
		printer.print(isField ? "field" : "variable");
		printer.print("\">");

		n.getName().accept(this, arg);

		printer.print("</span>");

		n.findAncestor(NodeWithVariables.class).ifPresent(ancestor -> {
			((NodeWithVariables<?>) ancestor).getMaximumCommonType().ifPresent(commonType -> {

				final Type type = n.getType();

				ArrayType arrayType = null;

				for (int i = commonType.getArrayLevel(); i < type.getArrayLevel(); i++) {
					if (arrayType == null) {
						arrayType = (ArrayType) type;
					} else {
						arrayType = (ArrayType) arrayType.getComponentType();
					}
					printAnnotations(arrayType.getAnnotations(), true, arg);
					printer.print("[]");
				}
			});
		});

		if (n.getInitializer().isPresent()) {
			printer.print(" = ");
			n.getInitializer().get().accept(this, arg);
		}
	}

	@Override
	public void visit(final ArrayInitializerExpr n, final Void arg) {
		printComment(n.getComment(), arg);
		printer.print("{");
		if (!isNullOrEmpty(n.getValues())) {
			printer.print(" ");
			for (final Iterator<Expression> i = n.getValues().iterator(); i.hasNext(); ) {
				final Expression expr = i.next();
				expr.accept(this, arg);
				if (i.hasNext()) {
					printer.print(", ");
				}
			}
			printer.print(" ");
		}
		printOrphanCommentsEnding(n);
		printer.print("}");
	}

	@Override
	public void visit(final VoidType n, final Void arg) {
		printComment(n.getComment(), arg);
		printAnnotations(n.getAnnotations(), false, arg);
		printer.print("<span class=\"keyword\">void</span>");
	}

	@Override
	public void visit(final VarType n, final Void arg) {
		printComment(n.getComment(), arg);
		printAnnotations(n.getAnnotations(), false, arg);
		printer.print("<span class=\"keyword\">var</span>");
	}

	@Override
	public void visit(final ArrayAccessExpr n, final Void arg) {
		printComment(n.getComment(), arg);
		n.getName().accept(this, arg);
		printer.print("[");
		n.getIndex().accept(this, arg);
		printer.print("]");
	}

	@Override
	public void visit(final ArrayCreationExpr n, final Void arg) {
		printComment(n.getComment(), arg);
		printer.print("<span class=\"keyword\">new</span> ");
		n.getElementType().accept(this, arg);
		for (ArrayCreationLevel level : n.getLevels()) {
			level.accept(this, arg);
		}
		if (n.getInitializer().isPresent()) {
			printer.print(" ");
			n.getInitializer().get().accept(this, arg);
		}
	}

	@Override
	public void visit(final AssignExpr n, final Void arg) {
		printComment(n.getComment(), arg);
		n.getTarget().accept(this, arg);
		printer.print(" ");
		printer.print(n.getOperator().asString());
		printer.print(" ");
		n.getValue().accept(this, arg);
	}

	@Override
	public void visit(final BinaryExpr n, final Void arg) {
		printComment(n.getComment(), arg);
		n.getLeft().accept(this, arg);
		printer.print(" ");
		printer.print(n.getOperator().asString());
		printer.print(" ");
		n.getRight().accept(this, arg);
	}

	@Override
	public void visit(final CastExpr n, final Void arg) {
		printComment(n.getComment(), arg);
		printer.print("(");
		n.getType().accept(this, arg);
		printer.print(") ");
		n.getExpression().accept(this, arg);
	}

	@Override
	public void visit(final ClassExpr n, final Void arg) {
		printComment(n.getComment(), arg);
		n.getType().accept(this, arg);
		printer.print("<span class=\"keyword\">.class</span>");
	}

	@Override
	public void visit(final ConditionalExpr n, final Void arg) {
		printComment(n.getComment(), arg);
		n.getCondition().accept(this, arg);
		printer.print(" ? ");
		n.getThenExpr().accept(this, arg);
		printer.print(" : ");
		n.getElseExpr().accept(this, arg);
	}

	@Override
	public void visit(final EnclosedExpr n, final Void arg) {
		printComment(n.getComment(), arg);
		printer.print("(");
		n.getInner().accept(this, arg);
		printer.print(")");
	}

	@Override
	public void visit(final FieldAccessExpr n, final Void arg) {
		printComment(n.getComment(), arg);
		n.getScope().accept(this, arg);
		printer.print(".");
		printer.print("<span class=\"field");
		String name = n.getNameAsString();
		if (name.toUpperCase(Locale.ROOT).equals(name)) {
			printer.print(" constant");
		}
		printer.print("\">");
		n.getName().accept(this, arg);
		printer.print("</span>");
	}

	@Override
	public void visit(final InstanceOfExpr n, final Void arg) {
		printComment(n.getComment(), arg);
		n.getExpression().accept(this, arg);
		printer.print(" <span class=\"keyword\">instanceof</span> ");
		n.getType().accept(this, arg);
	}

	@Override
	public void visit(final CharLiteralExpr n, final Void arg) {
		printComment(n.getComment(), arg);
		printer.print("<span class=\"string\">'");
		printer.print(HtmlUtil.escape(n.getValue()));
		printer.print("'</span>");
	}

	@Override
	public void visit(final DoubleLiteralExpr n, final Void arg) {
		printComment(n.getComment(), arg);
		printer.print("<span class=\"number\">");
		printer.print(n.getValue());
		printer.print("</span>");
	}

	@Override
	public void visit(final IntegerLiteralExpr n, final Void arg) {
		printComment(n.getComment(), arg);
		printer.print("<span class=\"number\">");
		printer.print(n.getValue());
		printer.print("</span>");
	}

	@Override
	public void visit(final LongLiteralExpr n, final Void arg) {
		printComment(n.getComment(), arg);
		printer.print("<span class=\"number\">");
		printer.print(n.getValue());
		printer.print("</span>");
	}

	@Override
	public void visit(final StringLiteralExpr n, final Void arg) {
		printComment(n.getComment(), arg);
		printer.print("<span class=\"string\">\"");
		printer.print(HtmlUtil.escape(n.getValue()));
		printer.print("\"</span>");
	}

	@Override
	public void visit(final BooleanLiteralExpr n, final Void arg) {
		printComment(n.getComment(), arg);

		printer.print("<span class=\"keyword\">");
		printer.print(String.valueOf(n.getValue()));
		printer.print("</span>");
	}

	@Override
	public void visit(final NullLiteralExpr n, final Void arg) {
		printComment(n.getComment(), arg);
		printer.print("<span class=\"keyword\">null</span>");
	}

	@Override
	public void visit(final ThisExpr n, final Void arg) {
		printComment(n.getComment(), arg);
		if (n.getClassExpr().isPresent()) {
			n.getClassExpr().get().accept(this, arg);
			printer.print(".");
		}
		printer.print("<span class=\"keyword\">this</span>");
	}

	@Override
	public void visit(final SuperExpr n, final Void arg) {
		printComment(n.getComment(), arg);
		if (n.getClassExpr().isPresent()) {
			n.getClassExpr().get().accept(this, arg);
			printer.print(".");
		}
		printer.print("<span class=\"keyword\">super</span>");
	}

	@Override
	public void visit(final MethodCallExpr n, final Void arg) {
		printComment(n.getComment(), arg);

		// determine whether we do reindenting for aligmnent at all
		// - is it enabled?
		// - are we in a statement where we want the alignment?
		// - are we not directly in the argument list of a method call expression?
		AtomicBoolean columnAlignFirstMethodChain = new AtomicBoolean();
		if (configuration.isColumnAlignFirstMethodChain()) {
			// pick the kind of expressions where vertically aligning method calls is okay.
			if (n.findAncestor(Statement.class).map(p -> p.isReturnStmt()
					|| p.isThrowStmt()
					|| p.isAssertStmt()
					|| p.isExpressionStmt()).orElse(false)) {
				// search for first parent that does not have its child as scope
				Node c = n;
				Optional<Node> p = c.getParentNode();
				while (p.isPresent() && p.filter(NodeWithTraversableScope.class::isInstance)
						.map(NodeWithTraversableScope.class::cast)
						.flatMap(NodeWithTraversableScope::traverseScope)
						.map(c::equals)
						.orElse(false)) {
					c = p.get();
					p = c.getParentNode();
				}

				// check if the parent is a method call and thus we are in an argument list
				columnAlignFirstMethodChain.set(!p.filter(MethodCallExpr.class::isInstance).isPresent());
			}
		}

		// we are at the last method call of a call chain
		// this means we do not start reindenting for alignment or we undo it
		AtomicBoolean lastMethodInCallChain = new AtomicBoolean(true);
		if (columnAlignFirstMethodChain.get()) {
			Node node = n;
			while (node.getParentNode()
					.filter(NodeWithTraversableScope.class::isInstance)
					.map(NodeWithTraversableScope.class::cast)
					.flatMap(NodeWithTraversableScope::traverseScope)
					.map(node::equals)
					.orElse(false)) {
				node = node.getParentNode().orElseThrow(AssertionError::new);
				if (node instanceof MethodCallExpr) {
					lastMethodInCallChain.set(false);
					break;
				}
			}
		}

		// search whether there is a method call with scope in the scope already
		// this means that we probably started reindenting for alignment there
		AtomicBoolean methodCallWithScopeInScope = new AtomicBoolean();
		if (columnAlignFirstMethodChain.get()) {
			Optional<Expression> s = n.getScope();
			while (s.filter(NodeWithTraversableScope.class::isInstance).isPresent()) {
				Optional<Expression> parentScope = s.map(NodeWithTraversableScope.class::cast)
						.flatMap(NodeWithTraversableScope::traverseScope);
				if (s.filter(MethodCallExpr.class::isInstance).isPresent() && parentScope.isPresent()) {
					methodCallWithScopeInScope.set(true);
					break;
				}
				s = parentScope;
			}
		}

		// we have a scope
		// this means we are not the first method in the chain
		n.getScope().ifPresent(scope -> {
			scope.accept(this, arg);
			if (columnAlignFirstMethodChain.get()) {
				if (methodCallWithScopeInScope.get()) {
					/* We're a method call on the result of something (method call, property access, ...) that is not stand alone,
                       and not the first one with scope, like:
                       we're x() in a.b().x(), or in a=b().c[15].d.e().x().
                       That means that the "else" has been executed by one of the methods in the scope chain, so that the alignment
                       is set to the "." of that method.
                       That means we will align to that "." when we start a new line: */
					printer.println();
				} else if (!lastMethodInCallChain.get()) {
					/* We're the first method call on the result of something in the chain (method call, property access, ...),
                       but we are not at the same time the last method call in that chain, like:
                       we're x() in a().x().y(), or in Long.x().y.z(). That means we get to dictate the indent of following method
                       calls in this chain by setting the cursor to where we are now: just before the "."
                       that start this method call. */
					printer.reindentWithAlignToCursor();
				}
			}
			printer.print(".");
		});

		printTypeArgs(n, arg);
		n.getName().accept(this, arg);
		printer.duplicateIndent();
		printArguments(n.getArguments(), arg);
		printer.unindent();
		if (columnAlignFirstMethodChain.get() && methodCallWithScopeInScope.get() && lastMethodInCallChain.get()) {
			// undo the aligning after the arguments of the last method call are printed
			printer.reindentToPreviousLevel();
		}
	}

	@Override
	public void visit(final ObjectCreationExpr n, final Void arg) {
		printComment(n.getComment(), arg);
		if (n.getScope().isPresent()) {
			n.getScope().get().accept(this, arg);
			printer.print(".");
		}

		printer.print("<span class=\"keyword\">new</span> ");

		printTypeArgs(n, arg);
		if (!isNullOrEmpty(n.getTypeArguments().orElse(null))) {
			printer.print(" ");
		}

		n.getType().accept(this, arg);

		printArguments(n.getArguments(), arg);

		if (n.getAnonymousClassBody().isPresent()) {
			printer.println(" {");
			printer.indent();
			printMembers(n.getAnonymousClassBody().get(), arg);
			printer.unindent();
			printer.print("}");
		}
	}

	@Override
	public void visit(final UnaryExpr n, final Void arg) {
		printComment(n.getComment(), arg);
		if (n.getOperator().isPrefix()) {
			printer.print(n.getOperator().asString());
		}

		n.getExpression().accept(this, arg);

		if (n.getOperator().isPostfix()) {
			printer.print(n.getOperator().asString());
		}
	}

	@Override
	public void visit(final ConstructorDeclaration n, final Void arg) {
		MethodInstance method = typeResolver.getMethod(n);

		if (method != null) {
			printer.unindent();
			printer.print("<span id=\"");
			printer.print(HtmlUtil.getId(method));
			printer.print("\">\t");
			printer.indent();
		}

		printComment(n.getComment(), arg);
		printMemberAnnotations(n.getAnnotations(), arg);
		printModifiers(n.getModifiers());

		printTypeParameters(n.getTypeParameters(), arg);
		if (n.isGeneric()) {
			printer.print(" ");
		}

		printer.print("<span class=\"constructor method name\">");
		printer.print(HtmlUtil.escape(n.getName().getIdentifier()));
		printer.print("</span>");

		printer.print("(");
		if (!n.getParameters().isEmpty()) {
			for (final Iterator<Parameter> i = n.getParameters().iterator(); i.hasNext(); ) {
				final Parameter p = i.next();
				p.accept(this, arg);
				if (i.hasNext()) {
					printer.print(", ");
				}
			}
		}
		printer.print(")");

		if (!isNullOrEmpty(n.getThrownExceptions())) {
			printer.print(" <span class=\"keyword\">throws</span> ");
			for (final Iterator<ReferenceType> i = n.getThrownExceptions().iterator(); i.hasNext(); ) {
				final ReferenceType name = i.next();
				name.accept(this, arg);
				if (i.hasNext()) {
					printer.print(", ");
				}
			}
		}
		printer.print(" ");
		n.getBody().accept(this, arg);

		if (method != null) {
			printer.print("</span>");
		}
	}

	@Override
	public void visit(final MethodDeclaration n, final Void arg) {
		MethodInstance method = typeResolver.getMethod(n);

		if (method != null) {
			printer.unindent();
			printer.print("<span id=\"");
			printer.print(HtmlUtil.getId(method));
			printer.print("\">\t");
			printer.indent();
		}

		printOrphanCommentsBeforeThisChildNode(n);

		printComment(n.getComment(), arg);
		printMemberAnnotations(n.getAnnotations(), arg);
		printModifiers(n.getModifiers());
		printTypeParameters(n.getTypeParameters(), arg);
		if (!isNullOrEmpty(n.getTypeParameters())) {
			printer.print(" ");
		}

		n.getType().accept(this, arg);
		printer.print(" <span class=\"method name\">");
		printer.print(HtmlUtil.escape(n.getName().getIdentifier()));
		printer.print("</span>");

		printer.print("(");
		n.getReceiverParameter().ifPresent(rp -> {
			rp.accept(this, arg);
			if (!isNullOrEmpty(n.getParameters())) {
				printer.print(", ");
			}
		});
		if (!isNullOrEmpty(n.getParameters())) {
			for (final Iterator<Parameter> i = n.getParameters().iterator(); i.hasNext(); ) {
				final Parameter p = i.next();
				p.accept(this, arg);
				if (i.hasNext()) {
					printer.print(", ");
				}
			}
		}
		printer.print(")");

		if (!isNullOrEmpty(n.getThrownExceptions())) {
			printer.print(" <span class=\"keyword\">throws</span> ");
			for (final Iterator<ReferenceType> i = n.getThrownExceptions().iterator(); i.hasNext(); ) {
				final ReferenceType name = i.next();
				name.accept(this, arg);
				if (i.hasNext()) {
					printer.print(", ");
				}
			}
		}
		if (!n.getBody().isPresent()) {
			printer.print(";");
		} else {
			printer.print(" ");
			n.getBody().get().accept(this, arg);
		}

		if (method != null) {
			printer.print("</span>");
		}
	}

	@Override
	public void visit(final Parameter n, final Void arg) {
		printComment(n.getComment(), arg);
		printAnnotations(n.getAnnotations(), false, arg);
		printModifiers(n.getModifiers());
		n.getType().accept(this, arg);
		if (n.isVarArgs()) {
			printAnnotations(n.getVarArgsAnnotations(), false, arg);
			printer.print("...");
		}
		if (!(n.getType() instanceof UnknownType)) {
			printer.print(" ");
		}

		printer.print("<span class=\"variable\">");
		n.getName().accept(this, arg);
		printer.print("</span>");
	}

	@Override
	public void visit(final ReceiverParameter n, final Void arg) {
		printComment(n.getComment(), arg);
		printAnnotations(n.getAnnotations(), false, arg);
		n.getType().accept(this, arg);
		printer.print(" ");
		n.getName().accept(this, arg);
	}

	@Override
	public void visit(final ExplicitConstructorInvocationStmt n, final Void arg) {
		printComment(n.getComment(), arg);
		if (n.isThis()) {
			printTypeArgs(n, arg);
			printer.print("<span class=\"keyword\">this</span>");
		} else {
			if (n.getExpression().isPresent()) {
				n.getExpression().get().accept(this, arg);
				printer.print(".");
			}
			printTypeArgs(n, arg);
			printer.print("<span class=\"keyword\">super</span>");
		}
		printArguments(n.getArguments(), arg);
		printer.print(";");
	}

	@Override
	public void visit(final VariableDeclarationExpr n, final Void arg) {
		printComment(n.getComment(), arg);
		if (n.getParentNode().map(ExpressionStmt.class::isInstance).orElse(false)) {
			printMemberAnnotations(n.getAnnotations(), arg);
		} else {
			printAnnotations(n.getAnnotations(), false, arg);
		}
		printModifiers(n.getModifiers());

		if (!n.getVariables().isEmpty()) {
			n.getMaximumCommonType().ifPresent(t -> t.accept(this, arg));
		}
		printer.print(" ");

		for (final Iterator<VariableDeclarator> i = n.getVariables().iterator(); i.hasNext(); ) {
			final VariableDeclarator v = i.next();
			v.accept(this, arg);
			if (i.hasNext()) {
				printer.print(", ");
			}
		}
	}

	@Override
	public void visit(final LocalClassDeclarationStmt n, final Void arg) {
		printComment(n.getComment(), arg);
		n.getClassDeclaration().accept(this, arg);
	}

	@Override
	public void visit(final AssertStmt n, final Void arg) {
		printComment(n.getComment(), arg);
		printer.print("<span class=\"keyword\">assert</span> ");
		n.getCheck().accept(this, arg);
		if (n.getMessage().isPresent()) {
			printer.print(" : ");
			n.getMessage().get().accept(this, arg);
		}
		printer.print(";");
	}

	@Override
	public void visit(final BlockStmt n, final Void arg) {
		printOrphanCommentsBeforeThisChildNode(n);
		printComment(n.getComment(), arg);
		printer.println("{");
		if (n.getStatements() != null) {
			printer.indent();
			for (final Statement s : n.getStatements()) {
				s.accept(this, arg);
				printer.println();
			}
			printer.unindent();
		}
		printOrphanCommentsEnding(n);
		printer.print("}");
	}

	@Override
	public void visit(final LabeledStmt n, final Void arg) {
		printComment(n.getComment(), arg);
		n.getLabel().accept(this, arg);
		printer.print(": ");
		n.getStatement().accept(this, arg);
	}

	@Override
	public void visit(final EmptyStmt n, final Void arg) {
		printComment(n.getComment(), arg);
		printer.print(";");
	}

	@Override
	public void visit(final ExpressionStmt n, final Void arg) {
		printOrphanCommentsBeforeThisChildNode(n);
		printComment(n.getComment(), arg);
		n.getExpression().accept(this, arg);
		printer.print(";");
	}

	@Override
	public void visit(final SwitchStmt n, final Void arg) {
		if (canAddNewLine(n)) printer.println();

		printComment(n.getComment(), arg);
		printer.print("<span class=\"keyword\">switch</span> (");
		n.getSelector().accept(this, arg);
		printer.println(") {");
		if (n.getEntries() != null) {
			for (final SwitchEntryStmt e : n.getEntries()) {
				e.accept(this, arg);
			}
		}
		printer.print("}");

		if (getNext(n) != null) printer.println();
	}

	@Override
	public void visit(final SwitchEntryStmt n, final Void arg) {
		printComment(n.getComment(), arg);
		if (n.getLabel().isPresent()) {
			printer.print("<span class=\"keyword\">case</span> ");
			n.getLabel().get().accept(this, arg);
			printer.print(":");
		} else {
			printer.print("<span class=\"keyword\">default</span>:");
		}

		if (n.getStatements() != null
				&& n.getStatements().size() == 1
				&& n.getStatements().get(0) instanceof BlockStmt) {
			printer.print(" ");
			n.getStatements().get(0).accept(this, arg);
			printer.println();
		} else {
			printer.println();

			if (n.getStatements() != null) {
				printer.indent();

				for (final Statement s : n.getStatements()) {
					s.accept(this, arg);
					printer.println();
				}

				printer.unindent();
			}
		}
	}

	@Override
	public void visit(final BreakStmt n, final Void arg) {
		printComment(n.getComment(), arg);
		printer.print("<span class=\"keyword\">break</span>");
		n.getLabel().ifPresent(l -> printer.print(" ").print(l.getIdentifier()));
		printer.print(";");
	}

	@Override
	public void visit(final ReturnStmt n, final Void arg) {
		printComment(n.getComment(), arg);
		printer.print("<span class=\"keyword\">return</span>");
		if (n.getExpression().isPresent()) {
			printer.print(" ");
			n.getExpression().get().accept(this, arg);
		}
		printer.print(";");
	}

	@Override
	public void visit(final EnumDeclaration n, final Void arg) {
		printComment(n.getComment(), arg);
		printMemberAnnotations(n.getAnnotations(), arg);
		printModifiers(n.getModifiers());

		printer.print("<span class=\"keyword\">enum</span> ");
		n.getName().accept(this, arg);

		if (!n.getImplementedTypes().isEmpty()) {
			printer.print(" <span class=\"keyword\">implements</span> ");
			for (final Iterator<ClassOrInterfaceType> i = n.getImplementedTypes().iterator(); i.hasNext(); ) {
				final ClassOrInterfaceType c = i.next();
				c.accept(this, arg);
				if (i.hasNext()) {
					printer.print(", ");
				}
			}
		}

		printer.println(" {");
		printer.indent();
		if (n.getEntries().isNonEmpty()) {
			final boolean alignVertically =
					// Either we hit the constant amount limit in the configurations, or...
					n.getEntries().size() > configuration.getMaxEnumConstantsToAlignHorizontally() ||
					// any of the constants has a comment.
					n.getEntries().stream().anyMatch(e -> e.getComment().isPresent());
					for (final Iterator<EnumConstantDeclaration> i = n.getEntries().iterator(); i.hasNext(); ) {
						final EnumConstantDeclaration e = i.next();
						e.accept(this, arg);
						if (i.hasNext()) {
							if (alignVertically) {
								printer.println(",");
							} else {
								printer.print(", ");
							}
						}
					}
		}
		if (!n.getMembers().isEmpty()) {
			printer.println(";");
			printer.println();
			printMembers(n.getMembers(), arg);
		} else {
			if (!n.getEntries().isEmpty()) {
				printer.println();
			}
		}
		printer.unindent();
		printer.print("}");
	}

	@Override
	public void visit(final EnumConstantDeclaration n, final Void arg) {
		FieldInstance field = typeResolver.getField(n);

		if (field != null) {
			printer.unindent();
			printer.print("<span id=\"");
			printer.print(HtmlUtil.getId(field));
			printer.print("\">\t");
			printer.indent();
		}

		printComment(n.getComment(), arg);
		printMemberAnnotations(n.getAnnotations(), arg);
		printer.print("<span class=\"enum name\">");
		printer.print(HtmlUtil.escape(n.getName().getIdentifier()));
		printer.print("</span>");

		if (!n.getArguments().isEmpty()) {
			printArguments(n.getArguments(), arg);
		}

		if (!n.getClassBody().isEmpty()) {
			printer.println(" {");
			printer.indent();
			printMembers(n.getClassBody(), arg);
			printer.unindent();
			printer.println("}");
		}

		if (field != null) printer.print("</span>");
	}

	@Override
	public void visit(final InitializerDeclaration n, final Void arg) {
		printComment(n.getComment(), arg);
		if (n.isStatic()) {
			printer.print("<span class=\"keyword\">static</span> ");
		}
		n.getBody().accept(this, arg);
	}

	@Override
	public void visit(final IfStmt n, final Void arg) {
		boolean thenBlock = n.getThenStmt() instanceof BlockStmt;

		while (thenBlock
				&& !n.getElseStmt().isPresent()
				&& ((BlockStmt) n.getThenStmt()).getStatements().size() == 1
				&& !(n.getParentNode().orElse(null) instanceof IfStmt)) {
			Statement stmt = ((BlockStmt) n.getThenStmt()).getStatements().get(0);
			if (isBlockStmt(stmt) && !(stmt instanceof BlockStmt)) break;

			n.setThenStmt(stmt);
			thenBlock = n.getThenStmt() instanceof BlockStmt;
		}

		Node prev = getPrev(n);

		if (thenBlock
				&& (canAddNewLine(n) || prev instanceof IfStmt && !((IfStmt) prev).hasThenBlock())
				&& !(n.getParentNode().orElse(null) instanceof IfStmt)) {
			printer.println();
		}

		printComment(n.getComment(), arg);
		printer.print("<span class=\"keyword\">if</span> (");
		n.getCondition().accept(this, arg);
		printer.print(") ");
		n.getThenStmt().accept(this, arg);

		Node next = getNext(n);

		if (n.getElseStmt().isPresent()) {
			Statement elseStmt = n.getElseStmt().get();

			if (thenBlock)
				printer.print(" ");
			else
				printer.println();
			final boolean elseIf = n.getElseStmt().orElse(null) instanceof IfStmt;
			final boolean elseBlock = n.getElseStmt().orElse(null) instanceof BlockStmt;
			if (elseIf || elseBlock) // put chained if and start of block statement on a same level
				printer.print("<span class=\"keyword\">else</span> ");
			else {
				printer.println("<span class=\"keyword\">else</span>");
				printer.indent();
			}

			elseStmt.accept(this, arg);

			if (!(elseIf || elseBlock))
				printer.unindent();

			if (next != null) printer.println();
		} else {
			if (next != null && (thenBlock || !(next instanceof IfStmt))) printer.println();
		}
	}

	@Override
	public void visit(final WhileStmt n, final Void arg) {
		if (canAddNewLine(n)) printer.println();

		printComment(n.getComment(), arg);
		printer.print("<span class=\"keyword\">while</span> (");
		n.getCondition().accept(this, arg);
		printer.print(") ");
		n.getBody().accept(this, arg);

		if (getNext(n) != null) printer.println();
	}

	@Override
	public void visit(final ContinueStmt n, final Void arg) {
		printComment(n.getComment(), arg);
		printer.print("<span class=\"keyword\">continue</span>");
		n.getLabel().ifPresent(l -> printer.print(" ").print(l.getIdentifier()));
		printer.print(";");
	}

	@Override
	public void visit(final DoStmt n, final Void arg) {
		if (canAddNewLine(n)) printer.println();

		printComment(n.getComment(), arg);
		printer.print("<span class=\"keyword\">do</span> ");
		n.getBody().accept(this, arg);
		printer.print(" <span class=\"keyword\">while</span> (");
		n.getCondition().accept(this, arg);
		printer.print(");");

		if (getNext(n) != null) printer.println();
	}

	@Override
	public void visit(final ForEachStmt n, final Void arg) {
		if (canAddNewLine(n)) printer.println();

		printComment(n.getComment(), arg);
		printer.print("<span class=\"keyword\">for</span> (");
		n.getVariable().accept(this, arg);
		printer.print(" : ");
		n.getIterable().accept(this, arg);
		printer.print(") ");
		n.getBody().accept(this, arg);

		if (getNext(n) != null) printer.println();
	}

	@Override
	public void visit(final ForStmt n, final Void arg) {
		if (canAddNewLine(n)) printer.println();

		printComment(n.getComment(), arg);
		printer.print("<span class=\"keyword\">for</span> (");
		if (n.getInitialization() != null) {
			for (final Iterator<Expression> i = n.getInitialization().iterator(); i.hasNext(); ) {
				final Expression e = i.next();
				e.accept(this, arg);
				if (i.hasNext()) {
					printer.print(", ");
				}
			}
		}
		printer.print("; ");
		if (n.getCompare().isPresent()) {
			n.getCompare().get().accept(this, arg);
		}
		printer.print("; ");
		if (n.getUpdate() != null) {
			for (final Iterator<Expression> i = n.getUpdate().iterator(); i.hasNext(); ) {
				final Expression e = i.next();
				e.accept(this, arg);
				if (i.hasNext()) {
					printer.print(", ");
				}
			}
		}
		printer.print(") ");
		n.getBody().accept(this, arg);

		if (getNext(n) != null) printer.println();
	}

	@Override
	public void visit(final ThrowStmt n, final Void arg) {
		printComment(n.getComment(), arg);
		printer.print("<span class=\"keyword\">throw</span> ");
		n.getExpression().accept(this, arg);
		printer.print(";");
	}

	@Override
	public void visit(final SynchronizedStmt n, final Void arg) {
		printComment(n.getComment(), arg);
		printer.print("<span class=\"keyword\">synchronized</span> (");
		n.getExpression().accept(this, arg);
		printer.print(") ");
		n.getBody().accept(this, arg);
	}

	@Override
	public void visit(final TryStmt n, final Void arg) {
		if (canAddNewLine(n)) printer.println();

		printComment(n.getComment(), arg);
		printer.print("<span class=\"keyword\">try</span> ");
		if (!n.getResources().isEmpty()) {
			printer.print("(");
			Iterator<Expression> resources = n.getResources().iterator();
			boolean first = true;
			while (resources.hasNext()) {
				resources.next().accept(this, arg);
				if (resources.hasNext()) {
					printer.print(";");
					printer.println();
					if (first) {
						printer.indent();
					}
				}
				first = false;
			}
			if (n.getResources().size() > 1) {
				printer.unindent();
			}
			printer.print(") ");
		}
		n.getTryBlock().accept(this, arg);
		for (final CatchClause c : n.getCatchClauses()) {
			c.accept(this, arg);
		}
		if (n.getFinallyBlock().isPresent()) {
			printer.print(" <span class=\"keyword\">finally</span> ");
			n.getFinallyBlock().get().accept(this, arg);
		}

		if (getNext(n) != null) printer.println();
	}

	@Override
	public void visit(final CatchClause n, final Void arg) {
		printComment(n.getComment(), arg);
		printer.print(" <span class=\"keyword\">catch</span> (");
		n.getParameter().accept(this, arg);
		printer.print(") ");
		n.getBody().accept(this, arg);
	}

	@Override
	public void visit(final AnnotationDeclaration n, final Void arg) {
		printComment(n.getComment(), arg);
		printMemberAnnotations(n.getAnnotations(), arg);
		printModifiers(n.getModifiers());

		printer.print("<span class=\"keyword\">@interface</span> <span class=\"annotation\">");
		n.getName().accept(this, arg);
		printer.println("</span> {");
		printer.indent();
		if (n.getMembers() != null) {
			printMembers(n.getMembers(), arg);
		}
		printer.unindent();
		printer.print("}");
	}

	@Override
	public void visit(final AnnotationMemberDeclaration n, final Void arg) {
		printComment(n.getComment(), arg);
		printMemberAnnotations(n.getAnnotations(), arg);
		printModifiers(n.getModifiers());

		n.getType().accept(this, arg);
		printer.print(" ");
		n.getName().accept(this, arg);
		printer.print("()");
		if (n.getDefaultValue().isPresent()) {
			printer.print(" <span class=\"keyword\">default</span> ");
			n.getDefaultValue().get().accept(this, arg);
		}
		printer.print(";");
	}

	@Override
	public void visit(final MarkerAnnotationExpr n, final Void arg) {
		printComment(n.getComment(), arg);
		printer.print("<span class=\"annotation\">@");
		n.getName().accept(this, arg);
		printer.print("</span>");
	}

	@Override
	public void visit(final SingleMemberAnnotationExpr n, final Void arg) {
		printComment(n.getComment(), arg);
		printer.print("<span class=\"annotation\">@");
		n.getName().accept(this, arg);
		printer.print("</span>(");
		n.getMemberValue().accept(this, arg);
		printer.print(")");
	}

	@Override
	public void visit(final NormalAnnotationExpr n, final Void arg) {
		printComment(n.getComment(), arg);
		printer.print("<span class=\"annotation\">@");
		n.getName().accept(this, arg);
		printer.print("</span>(");
		if (n.getPairs() != null) {
			for (final Iterator<MemberValuePair> i = n.getPairs().iterator(); i.hasNext(); ) {
				final MemberValuePair m = i.next();
				m.accept(this, arg);
				if (i.hasNext()) {
					printer.print(", ");
				}
			}
		}
		printer.print(")");
	}

	@Override
	public void visit(final MemberValuePair n, final Void arg) {
		printComment(n.getComment(), arg);
		n.getName().accept(this, arg);
		printer.print(" = ");
		n.getValue().accept(this, arg);
	}

	@Override
	public void visit(final LineComment n, final Void arg) {
		if (configuration.isIgnoreComments()) {
			return;
		}
		printer.print("<span class=\"comment\">//");
		printer
		.print("// ")
		.println(Utils.normalizeEolInTextBlock(HtmlUtil.escape(n.getContent()), "").trim());
		printer.println("</span>");
	}

	@Override
	public void visit(final BlockComment n, final Void arg) {
		if (configuration.isIgnoreComments()) {
			return;
		}
		final String commentContent = Utils.normalizeEolInTextBlock(n.getContent(), configuration.getEndOfLineCharacter());
		String[] lines = commentContent.split("\\R", -1); // as BlockComment should not be formatted, -1 to preserve any trailing empty line if present
		printer.print("<span class=\"comment\">/*");
		for (int i = 0; i < (lines.length - 1); i++) {
			printer.print(lines[i]);
			printer.print(configuration.getEndOfLineCharacter()); // Avoids introducing indentation in blockcomments. ie: do not use println() as it would trigger indentation at the next print call.
		}
		printer.print(lines[lines.length - 1]); // last line is not followed by a newline, and simply terminated with `*/`
		printer.println("*/</span>");
	}

	@Override
	public void visit(LambdaExpr n, Void arg) {
		printComment(n.getComment(), arg);

		final NodeList<Parameter> parameters = n.getParameters();
		final boolean printPar = n.isEnclosingParameters();

		if (printPar) {
			printer.print("(");
		}
		for (Iterator<Parameter> i = parameters.iterator(); i.hasNext(); ) {
			Parameter p = i.next();
			p.accept(this, arg);
			if (i.hasNext()) {
				printer.print(", ");
			}
		}
		if (printPar) {
			printer.print(")");
		}

		printer.print(" -> ");
		final Statement body = n.getBody();
		if (body instanceof ExpressionStmt) {
			// Print the expression directly
			((ExpressionStmt) body).getExpression().accept(this, arg);
		} else {
			body.accept(this, arg);
		}
	}

	@Override
	public void visit(MethodReferenceExpr n, Void arg) {
		printComment(n.getComment(), arg);
		Expression scope = n.getScope();
		String identifier = n.getIdentifier();
		if (scope != null) {
			n.getScope().accept(this, arg);
		}

		printer.print("::");
		printTypeArgs(n, arg);
		if (identifier != null) {
			printer.print(identifier);
		}
	}

	@Override
	public void visit(TypeExpr n, Void arg) {
		printComment(n.getComment(), arg);
		if (n.getType() != null) {
			n.getType().accept(this, arg);
		}
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	@Override
	public void visit(NodeList n, Void arg) {
		if (configuration.isOrderImports() && n.size() > 0 && n.get(0) instanceof ImportDeclaration) {
			//noinspection unchecked
			NodeList<ImportDeclaration> modifiableList = new NodeList<>(n);
			modifiableList.sort(
					Comparator.comparingInt((ImportDeclaration i) -> i.isStatic() ? 0 : 1)
					.thenComparing(NodeWithName::getNameAsString));
			for (Object node : modifiableList) {
				((Node) node).accept(this, arg);
			}
		} else {
			for (Object node : n) {
				((Node) node).accept(this, arg);
			}
		}
	}

	@Override
	public void visit(final ImportDeclaration n, final Void arg) {
		printComment(n.getComment(), arg);
		printer.print("<span class=\"keyword\">import</span> ");
		if (n.isStatic()) {
			printer.print("<span class=\"keyword\">static</span> ");
		}
		n.getName().accept(this, arg);
		if (n.isAsterisk()) {
			printer.print(".*");
		}
		printer.println(";");

		printOrphanCommentsEnding(n);
	}


	@Override
	public void visit(ModuleDeclaration n, Void arg) {
		printMemberAnnotations(n.getAnnotations(), arg);
		printer.println();
		if (n.isOpen()) {
			printer.print("<span class=\"keyword\">open</span> ");
		}
		printer.print("<span class=\"keyword\">module</span> ");
		n.getName().accept(this, arg);
		printer.println(" {").indent();
		n.getDirectives().accept(this, arg);
		printer.unindent().println("}");
	}

	@Override
	public void visit(ModuleRequiresDirective n, Void arg) {
		printer.print("<span class=\"keyword\">requires</span> ");
		printModifiers(n.getModifiers());
		n.getName().accept(this, arg);
		printer.println(";");
	}

	@Override
	public void visit(ModuleExportsDirective n, Void arg) {
		printer.print("<span class=\"keyword\">exports</span> ");
		n.getName().accept(this, arg);
		printPrePostFixOptionalList(n.getModuleNames(), arg, " to ", ", ", "");
		printer.println(";");
	}

	@Override
	public void visit(ModuleProvidesDirective n, Void arg) {
		printer.print("<span class=\"keyword\">provides</span> ");
		n.getName().accept(this, arg);
		printPrePostFixRequiredList(n.getWith(), arg, " with ", ", ", "");
		printer.println(";");
	}

	@Override
	public void visit(ModuleUsesDirective n, Void arg) {
		printer.print("<span class=\"keyword\">uses</span> ");
		n.getName().accept(this, arg);
		printer.println(";");
	}

	@Override
	public void visit(ModuleOpensDirective n, Void arg) {
		printer.print("<span class=\"keyword\">opens</span> ");
		n.getName().accept(this, arg);
		printPrePostFixOptionalList(n.getModuleNames(), arg, " to ", ", ", "");
		printer.println(";");
	}

	@Override
	public void visit(UnparsableStmt n, Void arg) {
		printer.print("???;");
	}

	private void printOrphanCommentsBeforeThisChildNode(final Node node) {
		if (configuration.isIgnoreComments()) return;
		if (node instanceof Comment) return;

		Node parent = node.getParentNode().orElse(null);
		if (parent == null) return;
		List<Node> everything = new LinkedList<>();
		everything.addAll(parent.getChildNodes());
		sortByBeginPosition(everything);
		int positionOfTheChild = -1;
		for (int i = 0; i < everything.size(); i++) {
			if (everything.get(i) == node) positionOfTheChild = i;
		}
		if (positionOfTheChild == -1) {
			throw new AssertionError("I am not a child of my parent.");
		}
		int positionOfPreviousChild = -1;
		for (int i = positionOfTheChild - 1; i >= 0 && positionOfPreviousChild == -1; i--) {
			if (!(everything.get(i) instanceof Comment)) positionOfPreviousChild = i;
		}
		for (int i = positionOfPreviousChild + 1; i < positionOfTheChild; i++) {
			Node nodeToPrint = everything.get(i);
			if (!(nodeToPrint instanceof Comment))
				throw new RuntimeException(
						"Expected comment, instead " + nodeToPrint.getClass() + ". Position of previous child: "
								+ positionOfPreviousChild + ", position of child " + positionOfTheChild);
			nodeToPrint.accept(this, null);
		}
	}

	private void printOrphanCommentsEnding(final Node node) {
		if (configuration.isIgnoreComments()) return;

		List<Node> everything = new LinkedList<>();
		everything.addAll(node.getChildNodes());
		sortByBeginPosition(everything);
		if (everything.isEmpty()) {
			return;
		}

		int commentsAtEnd = 0;
		boolean findingComments = true;
		while (findingComments && commentsAtEnd < everything.size()) {
			Node last = everything.get(everything.size() - 1 - commentsAtEnd);
			findingComments = (last instanceof Comment);
			if (findingComments) {
				commentsAtEnd++;
			}
		}
		for (int i = 0; i < commentsAtEnd; i++) {
			everything.get(everything.size() - commentsAtEnd + i).accept(this, null);
		}
	}

	private static boolean canAddNewLine(Node n) {
		Node prev = getPrev(n);

		return prev != null && !isBlockStmt(prev);
	}

	private static boolean isBlockStmt(Node n) {
		return n instanceof BlockStmt
				|| n instanceof DoStmt
				|| n instanceof ForStmt
				|| n instanceof ForEachStmt
				|| n instanceof IfStmt
				|| n instanceof SwitchStmt
				|| n instanceof TryStmt
				|| n instanceof WhileStmt;
	}

	private static Node getPrev(Node n) {
		Node parent = n.getParentNode().orElse(null);
		if (parent == null) return null;

		int idx = parent.getChildNodes().indexOf(n);
		if (idx == 0) return null;

		return parent.getChildNodes().get(idx - 1);
	}

	private static Node getNext(Node n) {
		Node parent = n.getParentNode().orElse(null);
		if (parent == null) return null;

		int idx = parent.getChildNodes().indexOf(n);
		if (idx == parent.getChildNodes().size() - 1) return null;

		return parent.getChildNodes().get(idx + 1);
	}

	protected final TypeResolver typeResolver;
	protected final PrettyPrinterConfiguration configuration;
	protected final SourcePrinter printer;
}
