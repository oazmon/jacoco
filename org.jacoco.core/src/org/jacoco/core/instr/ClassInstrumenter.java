/*******************************************************************************
 * Copyright (c) 2009 Mountainminds GmbH & Co. KG and others
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Marc R. Hoffmann - initial API and implementation
 *    
 * $Id: $
 *******************************************************************************/
package org.jacoco.core.instr;

import java.util.ArrayList;
import java.util.List;

import org.jacoco.core.runtime.IRuntime;
import org.objectweb.asm.ClassAdapter;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.EmptyVisitor;
import org.objectweb.asm.commons.GeneratorAdapter;

/**
 * Adapter that instruments a class for coverage tracing.
 * 
 * @author Marc R. Hoffmann
 * @version $Revision: $
 */
public class ClassInstrumenter extends ClassAdapter {

	private static class EmptyBlockMethodVisitor extends EmptyVisitor implements
			IBlockMethodVisitor {

		public void visitBlockEndBeforeJump(final int id) {
		}

		public void visitBlockEnd(final int id) {
		}

	}

	private final long id;

	private final IRuntime runtime;

	private final List<BlockMethodAdapter> blockCounters;

	private Type type;

	/**
	 * Emits a instrumented version of this class to the given class visitor
	 * 
	 * @param id
	 *            unique identifier given to this class
	 * @param runtime
	 *            this runtime will be used for instrumentation
	 * @param cv
	 *            next delegate in the visitor chain will receive the
	 *            instrumented class
	 */
	public ClassInstrumenter(final long id, final IRuntime runtime,
			final ClassVisitor cv) {
		super(cv);
		this.id = id;
		this.runtime = runtime;
		this.blockCounters = new ArrayList<BlockMethodAdapter>();
	}

	@Override
	public void visit(final int version, final int access, final String name,
			final String signature, final String superName,
			final String[] interfaces) {
		this.type = Type.getObjectType(name);
		super.visit(version, access, name, signature, superName, interfaces);
	}

	@Override
	public MethodVisitor visitMethod(final int access, final String name,
			final String desc, final String signature, final String[] exceptions) {

		final MethodVisitor mv = super.visitMethod(access, name, desc,
				signature, exceptions);

		// Abstract methods do not have code to analyze
		if ((access & Opcodes.ACC_ABSTRACT) != 0) {
			return mv;
		}

		final int methodId = blockCounters.size();

		final IBlockMethodVisitor blockVisitor;
		if (mv == null) {
			blockVisitor = new EmptyBlockMethodVisitor();
		} else {
			blockVisitor = new MethodInstrumenter(mv, access, name, desc,
					methodId, type);
		}

		final BlockMethodAdapter adapter = new BlockMethodAdapter(blockVisitor,
				access, name, desc, signature, exceptions);
		blockCounters.add(adapter);
		return adapter;
	}

	@Override
	public void visitEnd() {
		createDataField();
		createInitMethod();
		super.visitEnd();
	}

	private void createDataField() {
		super.visitField(GeneratorConstants.DATAFIELD_ACC,
				GeneratorConstants.DATAFIELD_NAME,
				GeneratorConstants.DATAFIELD_TYPE.getDescriptor(), null, null);
	}

	private void createInitMethod() {
		final int access = GeneratorConstants.INIT_METHOD_ACC;
		final String name = GeneratorConstants.INIT_METHOD.getName();
		final String desc = GeneratorConstants.INIT_METHOD.getDescriptor();
		final GeneratorAdapter gen = new GeneratorAdapter(super.visitMethod(
				access, name, desc, null, null), access, name, desc);

		// Load the value of the static data field:
		gen.getStatic(type, GeneratorConstants.DATAFIELD_NAME,
				GeneratorConstants.DATAFIELD_TYPE);
		gen.dup();

		// .............................................. Stack: [[Z, [[Z

		// Skip initialization when we already have a data array:
		final Label alreadyInitialized = new Label();
		gen.ifNonNull(alreadyInitialized);

		// .............................................. Stack: [[Z

		gen.pop();
		genInitializeDataField(gen);

		// .............................................. Stack: [[Z

		// Return the method's block array:
		gen.visitLabel(alreadyInitialized);
		gen.loadArg(0);
		gen.arrayLoad(GeneratorConstants.BLOCK_ARR);
		gen.returnValue();
		gen.endMethod();
	}

	/**
	 * Generates the byte code to initialize the static coverage data field
	 * within this class.
	 * 
	 * The code will push the [[Z data array on the operand stack.
	 * 
	 * @param gen
	 *            generator to emit code to
	 */
	private void genInitializeDataField(final GeneratorAdapter gen) {
		genInstantiateDataArray(gen); // ................ Stack: [[Z
		gen.dup(); // ................................... Stack: [[Z [[Z
		gen.putStatic(type, GeneratorConstants.DATAFIELD_NAME,
				GeneratorConstants.DATAFIELD_TYPE);

		// .............................................. Stack: [[Z

		gen.dup(); // ................................... Stack: [[Z [[Z
		runtime.generateRegistration(id, gen);

		// .............................................. Stack: [[Z
	}

	/**
	 * Generates the byte code to instantiate the 2-dimensional block data
	 * array. Each boolean[] entry is created in a length equals to the number
	 * of blocks in the corresponding method.
	 * 
	 * The code will push the [[Z data array on the operand stack.
	 * 
	 * TODO: Let the coverage runtime generate this structure
	 * 
	 * @param gen
	 *            generator to emit code to
	 */
	private void genInstantiateDataArray(final GeneratorAdapter gen) {
		gen.push(blockCounters.size()); // .............. Stack: I
		gen.newArray(GeneratorConstants.BLOCK_ARR); // .. Stack: [[Z
		for (int blockIdx = 0; blockIdx < blockCounters.size(); blockIdx++) {
			gen.dup(); // ............................... Stack: [[Z, [[Z
			gen.push(blockIdx); // ...................... Stack: [[Z, [[Z, I
			gen.push(blockCounters.get(blockIdx).getBlockCount());
			// .......................................... Stack: [[Z, [[Z, I, I
			gen.newArray(Type.BOOLEAN_TYPE);// .......... Stack: [[Z, [[Z, I, [Z
			gen.arrayStore(GeneratorConstants.BLOCK_ARR); // Stack: [[Z
		}
	}

}
