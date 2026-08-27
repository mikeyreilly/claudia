package com.quaxt.codingagent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.lang.classfile.ClassFile;
import java.lang.constant.ConstantDescs;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeModel;
import java.lang.classfile.Instruction;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LoadInstruction;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class DataCarrierArchitectureTest {
	private static final String OPERATIONS_CLASS = "com.quaxt.codingagent.CodingAgentOperations";

	@Test
	void productionTypesAreMutableDataCarriers() throws Exception {
		Path root = packageRoot();
		List<String> classNames = productionClassFiles(root).stream()
				.map(classFile -> className(root, classFile))
				.sorted()
				.toList();

		List<String> violations = new ArrayList<>();
		for (String className : classNames) {
			Class<?> type = Class.forName(className, false, getClass().getClassLoader());
			if (type.isSynthetic() || type.isEnum()) {
				continue;
			}
			assertFalse(type.isRecord(), () -> type.getName() + " must be a mutable class, not a record");
			for (Method method : type.getDeclaredMethods()) {
				if (!method.isSynthetic()
						&& !method.isBridge()
						&& !method.getName().equals("equals")
						&& !method.getName().equals("hashCode")
						&& !method.getName().equals("toString")) {
					violations.add(type.getName() + "#" + method.getName());
				}
			}
			for (Field field : type.getDeclaredFields()) {
				if (!field.isSynthetic() && !Modifier.isPublic(field.getModifiers())) {
					violations.add(type.getName() + "." + field.getName() + " is not public");
				}
				if (!field.isSynthetic()
						&& !Modifier.isStatic(field.getModifiers())
						&& Modifier.isFinal(field.getModifiers())) {
					violations.add(type.getName() + "." + field.getName() + " is final");
				}
			}
		}

		assertTrue(violations.isEmpty(), () -> String.join("\n", violations));
	}

	/**
	 * Every constructor parameter must flow straight into a field of the
	 * declaring class (or into a superclass constructor for exception types).
	 * Any other consumer of a parameter - a validator, {@code List.copyOf},
	 * a null-defaulting branch, or a delegating {@code this(...)} call - is
	 * behavior that belongs in {@link CodingAgentOperations}. Inline field
	 * initializers are compiled into constructors too, but they can never read
	 * a parameter, so they are unaffected by this rule.
	 */
	@Test
	void productionConstructorsOnlyAssignParametersToFields() throws Exception {
		Path root = packageRoot();
		List<String> violations = new ArrayList<>();
		for (Path classFile : productionClassFiles(root)) {
			ClassModel model = ClassFile.of().parse(classFile);
			String owner = model.thisClass().asInternalName();
			for (MethodModel method : model.methods()) {
				if (!method.methodName().equalsString(ConstantDescs.INIT_NAME)) {
					continue;
				}
				CodeModel code = method.code().orElse(null);
				if (code == null) {
					continue;
				}
				List<Instruction> instructions = new ArrayList<>();
				for (CodeElement element : code) {
					if (element instanceof Instruction instruction) {
						instructions.add(instruction);
					}
				}
				String signature = className(root, classFile) + method.methodType().stringValue();
				for (int i = 0; i < instructions.size(); i++) {
					Instruction instruction = instructions.get(i);
					if (instruction instanceof InvokeInstruction invoke
							&& invoke.opcode() == Opcode.INVOKESPECIAL
							&& invoke.name().equalsString(ConstantDescs.INIT_NAME)
							&& invoke.owner().asInternalName().equals(owner)) {
						violations.add(signature + " delegates to another constructor");
						continue;
					}
					if (!(instruction instanceof LoadInstruction load) || load.slot() == 0) {
						continue;
					}
					Instruction next = i + 1 < instructions.size() ? instructions.get(i + 1) : null;
					boolean assigned = switch (next) {
						case LoadInstruction ignored -> true;
						case FieldInstruction field -> field.opcode() == Opcode.PUTFIELD
								&& field.owner().asInternalName().equals(owner);
						case InvokeInstruction invoke -> invoke.opcode() == Opcode.INVOKESPECIAL
								&& invoke.name().equalsString(ConstantDescs.INIT_NAME);
						case null, default -> false;
					};
					if (!assigned) {
						violations.add(signature + " passes a parameter to "
								+ (next == null ? "nothing" : next.opcode())
								+ " instead of assigning it to a field");
					}
				}
			}
		}

		assertTrue(violations.isEmpty(), () -> "Data carrier constructors must only assign parameters to fields:\n"
				+ String.join("\n", violations));
	}

	private static List<Path> productionClassFiles(Path root) throws Exception {
		try (Stream<Path> files = Files.walk(root)) {
			return files
					.filter(path -> path.toString().endsWith(".class"))
					.filter(path -> !className(root, path).startsWith(OPERATIONS_CLASS))
					.sorted()
					.toList();
		}
	}

	private static Path packageRoot() throws Exception {
		return Path.of(CodingAgentOperations.class
						.getProtectionDomain()
						.getCodeSource()
						.getLocation()
						.toURI())
				.resolve("com/quaxt/codingagent");
	}

	private static String className(Path root, Path classFile) {
		String relative = root.relativize(classFile).toString();
		return "com.quaxt.codingagent."
				+ relative.substring(0, relative.length() - ".class".length())
						.replace(File.separatorChar, '.');
	}
}
