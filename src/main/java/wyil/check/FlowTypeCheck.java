// Copyright 2011 The Whiley Project Developers
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package wyil.check;

import static wybs.util.AbstractCompilationUnit.ITEM_bool;
import static wybs.util.AbstractCompilationUnit.ITEM_int;
import static wybs.util.AbstractCompilationUnit.ITEM_null;

import java.io.IOException;
import java.util.*;

import wybs.lang.*;
import wybs.util.AbstractCompilationUnit.Identifier;
import wybs.util.AbstractCompilationUnit.Name;
import wybs.util.AbstractCompilationUnit.Tuple;
import wybs.util.AbstractCompilationUnit.Value;
import wyc.util.ErrorMessages;
import wycc.util.ArrayUtils;
import wyil.check.FlowTypeUtils.Environment;
import wyil.lang.Compiler;
import wyil.lang.WyilFile;
import wyil.lang.WyilFile.Decl;
import wyil.lang.WyilFile.Type;
import wyil.type.binding.RelaxedTypeResolver;
import wyil.type.binding.TypeResolver;
import wyil.type.subtyping.EmptinessTest;
import wyil.type.subtyping.EmptinessTest.LifetimeRelation;
import wyil.type.subtyping.RelaxedTypeEmptinessTest;
import wyil.type.subtyping.StrictTypeEmptinessTest;
import wyil.type.subtyping.SubtypeOperator;
import wyil.type.util.ConcreteTypeExtractor;
import wyil.type.util.ReadWriteTypeExtractor;
import wyil.util.AbstractVisitor;

import static wyil.lang.WyilFile.*;

/**
 * <p>
 * Propagates type information in a <i>flow-sensitive</i> fashion from declared
 * parameter and return types through variable declarations and assigned
 * expressions, whilst inferring types for all intermediate expressions and
 * variables. During this propagation, type checking is performed to ensure
 * types are used soundly. For example:
 * </p>
 *
 * <pre>
 * function sum(int[] data) -> int:
 *     int r = 0                // declared int type for r
 *     int i = 0                // declared int type for i
 *     while i < |data|:        // checks int operands and bool condition
 *         r = r + data[i]      // checks int operands and int subscript
 *         i = i + 1            // checks int operands
 *     return r                 // checks int operand
 * </pre>
 *
 * <p>
 * The flow typing algorithm distinguishes between the <i>declared type</i> of a
 * variable and its <i>known type</i>. That is, the known type at any given
 * point is permitted to be more precise than the declared type (but not vice
 * versa). For example:
 * </p>
 *
 * <pre>
 * function extract(int|null x) -> int:
 *    if x is int:
 *        return y
 *    else:
 *        return 0
 * </pre>
 *
 * <p>
 * The above example is considered type safe because the known type of
 * <code>x</code> at the first return is <code>int</code>, which differs from
 * its declared type (i.e. <code>int|null</code>).
 * </p>
 *
 * <h3>References</h3>
 * <ul>
 * <li>
 * <p>
 * David J. Pearce and James Noble. Structural and Flow-Sensitive Types for
 * Whiley. Technical Report, Victoria University of Wellington, 2010.
 * </p>
 * </li>
 * </ul>
 *
 * @author David J. Pearce
 *
 */
public class FlowTypeCheck implements Compiler.Check {
	private final SubtypeOperator relaxedSubtypeOperator;
	private final SubtypeOperator strictSubtypeOperator;
	private final ConcreteTypeExtractor concreteTypeExtractor;
	private final ReadWriteTypeExtractor rwTypeExtractor;
	private final TypeResolver typeInference;
	private boolean status = true;

	public FlowTypeCheck() {
		EmptinessTest<SemanticType> strictEmptiness = new StrictTypeEmptinessTest();
		this.concreteTypeExtractor = new ConcreteTypeExtractor(strictEmptiness);
		this.relaxedSubtypeOperator = new SubtypeOperator(new RelaxedTypeEmptinessTest());
		this.strictSubtypeOperator = new SubtypeOperator(strictEmptiness);
		this.rwTypeExtractor = new ReadWriteTypeExtractor(strictSubtypeOperator);
		this.typeInference = new RelaxedTypeResolver(relaxedSubtypeOperator, concreteTypeExtractor, rwTypeExtractor);
	}

	// =========================================================================
	// WhileyFile(s)
	// =========================================================================

	@Override
	public boolean check(WyilFile wf) {
		for (Decl decl : wf.getModule().getUnits()) {
			checkDeclaration(decl);
		}
		return status;
	}

	// =========================================================================
	// Declarations
	// =========================================================================

	public void checkDeclaration(Decl decl) {
		if (decl instanceof Decl.Unit) {
			checkUnit((Decl.Unit) decl);
		} else if (decl instanceof Decl.Import) {
			// Can ignore
		} else if (decl instanceof Decl.StaticVariable) {
			checkStaticVariableDeclaration((Decl.StaticVariable) decl);
		} else if (decl instanceof Decl.Type) {
			checkTypeDeclaration((Decl.Type) decl);
		} else if (decl instanceof Decl.FunctionOrMethod) {
			checkFunctionOrMethodDeclaration((Decl.FunctionOrMethod) decl);
		} else {
			checkPropertyDeclaration((Decl.Property) decl);
		}
	}

	public void checkUnit(Decl.Unit unit) {
		for (Decl decl : unit.getDeclarations()) {
			checkDeclaration(decl);
		}
	}

	/**
	 * Resolve types for a given type declaration. If an invariant expression is
	 * given, then we have to check and resolve types throughout the expression.
	 *
	 * @param td
	 *            Type declaration to check.
	 * @throws IOException
	 */
	public void checkTypeDeclaration(Decl.Type decl) {
		Environment environment = new Environment();
		// Check type is contractive
		checkContractive(decl);
		// Check variable declaration is not empty
		checkVariableDeclaration(decl.getVariableDeclaration(), environment);
		// Check the type invariant
		checkConditions(decl.getInvariant(), true, environment);
	}

	/**
	 * check and check types for a given constant declaration.
	 *
	 * @param cd
	 *            Constant declaration to check.
	 * @throws IOException
	 */
	public void checkStaticVariableDeclaration(Decl.StaticVariable decl) {
		Environment environment = new Environment();
		// Check type not void
		checkVariableDeclaration(decl, environment);
	}

	/**
	 * Type check a given function or method declaration.
	 *
	 * @param fd
	 *            Function or method declaration to check.
	 * @throws IOException
	 */
	public void checkFunctionOrMethodDeclaration(Decl.FunctionOrMethod d) {
		// Construct initial environment
		Environment environment = new Environment();
		// Update environment so this within declared lifetimes
		environment = FlowTypeUtils.declareThisWithin(d, environment);
		// Check parameters and returns are not empty (i.e. are not equivalent
		// to void, as this is non-sensical).
		checkVariableDeclarations(d.getParameters(), environment);
		checkVariableDeclarations(d.getReturns(), environment);
		// Check any preconditions (i.e. requires clauses) provided.
		checkConditions(d.getRequires(), true, environment);
		// Check any postconditions (i.e. ensures clauses) provided.
		checkConditions(d.getEnsures(), true, environment);
		// FIXME: Add the "this" lifetime
		if (d.getModifiers().match(Modifier.Native.class) == null) {
			// Create scope representing this declaration
			EnclosingScope scope = new FunctionOrMethodScope(d);
			// Check type information throughout all statements in body.
			Environment last = checkBlock(d.getBody(), environment, scope);
			// Check return value
			checkReturnValue(d, last);
		} else {
			// NOTE: we obviously don't need to check the body of a native function or
			// method. Attempting to do so causes problems because checkReturnValue will
			// fail.
		}
	}

	/**
	 * Check that a return value is provided when it is needed. For example, a
	 * return value is not required for a method that has no return type. Likewise,
	 * we don't expect one from a native method since there was no body to analyse.
	 *
	 * @param d
	 * @param last
	 */
	private void checkReturnValue(Decl.FunctionOrMethod d, Environment last) {
		if (d.match(Modifier.Native.class) == null && last != FlowTypeUtils.BOTTOM && d.getReturns().size() != 0) {
			// In this case, code reaches the end of the function or method and,
			// furthermore, that this requires a return value. To get here means
			// that there was no explicit return statement given on at least one
			// execution path.
			syntaxError(d, MISSING_RETURN_STATEMENT);
		}
	}

	public void checkPropertyDeclaration(Decl.Property d) {
		// Construct initial environment
		Environment environment = new Environment();
		// Check parameters and returns are not empty (i.e. are not equivalent
		// to void, as this is non-sensical).
		checkVariableDeclarations(d.getParameters(), environment);
		checkVariableDeclarations(d.getReturns(), environment);
		// Check invariant (i.e. requires clauses) provided.
		checkConditions(d.getInvariant(), true, environment);
	}

	// =========================================================================
	// Blocks & Statements
	// =========================================================================

	/**
	 * check type information in a flow-sensitive fashion through a block of
	 * statements, whilst type checking each statement and expression.
	 *
	 * @param block
	 *            Block of statements to flow sensitively type check
	 * @param environment
	 *            Determines the type of all variables immediately going into this
	 *            block
	 * @return
	 */
	private Environment checkBlock(Stmt.Block block, Environment environment, EnclosingScope scope) {
		for (int i = 0; i != block.size(); ++i) {
			Stmt stmt = block.get(i);
			environment = checkStatement(stmt, environment, scope);
		}
		return environment;
	}

	/**
	 * check type information in a flow-sensitive fashion through a given statement,
	 * whilst type checking it at the same time. For statements which contain other
	 * statements (e.g. if, while, etc), then this will recursively check type
	 * information through them as well.
	 *
	 *
	 * @param forest
	 *            Block of statements to flow-sensitively type check
	 * @param environment
	 *            Determines the type of all variables immediately going into this
	 *            block
	 * @return
	 */
	private Environment checkStatement(Stmt stmt, Environment environment, EnclosingScope scope) {
		try {
			// FIXME: should be using a switch statement here!!
			if (environment == FlowTypeUtils.BOTTOM) {
				// Sanity check incoming environment
				syntaxError(stmt, UNREACHABLE_CODE);
				return environment;
			} else if (stmt instanceof Decl.Variable) {
				return checkVariableDeclaration((Decl.Variable) stmt, environment);
			} else if (stmt instanceof Stmt.Assign) {
				return checkAssign((Stmt.Assign) stmt, environment, scope);
			} else if (stmt instanceof Stmt.Return) {
				return checkReturn((Stmt.Return) stmt, environment, scope);
			} else if (stmt instanceof Stmt.IfElse) {
				return checkIfElse((Stmt.IfElse) stmt, environment, scope);
			} else if (stmt instanceof Stmt.NamedBlock) {
				return checkNamedBlock((Stmt.NamedBlock) stmt, environment, scope);
			} else if (stmt instanceof Stmt.While) {
				return checkWhile((Stmt.While) stmt, environment, scope);
			} else if (stmt instanceof Stmt.Switch) {
				return checkSwitch((Stmt.Switch) stmt, environment, scope);
			} else if (stmt instanceof Stmt.DoWhile) {
				return checkDoWhile((Stmt.DoWhile) stmt, environment, scope);
			} else if (stmt instanceof Stmt.Break) {
				return checkBreak((Stmt.Break) stmt, environment, scope);
			} else if (stmt instanceof Stmt.Continue) {
				return checkContinue((Stmt.Continue) stmt, environment, scope);
			} else if (stmt instanceof Stmt.Assert) {
				return checkAssert((Stmt.Assert) stmt, environment, scope);
			} else if (stmt instanceof Stmt.Assume) {
				return checkAssume((Stmt.Assume) stmt, environment, scope);
			} else if (stmt instanceof Stmt.Fail) {
				return checkFail((Stmt.Fail) stmt, environment, scope);
			} else if (stmt instanceof Stmt.Debug) {
				return checkDebug((Stmt.Debug) stmt, environment, scope);
			} else if (stmt instanceof Stmt.Skip) {
				return checkSkip((Stmt.Skip) stmt, environment, scope);
			} else if (stmt instanceof Expr.Invoke) {
				checkInvoke((Expr.Invoke) stmt, environment);
				return environment;
			} else if (stmt instanceof Expr.IndirectInvoke) {
				checkIndirectInvoke((Expr.IndirectInvoke) stmt, environment);
				return environment;
			} else {
				return internalFailure("unknown statement: " + stmt.getClass().getName(), stmt);
			}
		} catch (Throwable e) {
			return internalFailure(e.getMessage(), stmt, e);
		}
	}

	/**
	 * Type check an assertion statement. This requires checking that the expression
	 * being asserted is well-formed and has boolean type. An assert statement can
	 * affect the resulting environment in certain cases, such as when a type test
	 * is assert. For example, after <code>assert x is int</code> the environment
	 * will regard <code>x</code> as having type <code>int</code>.
	 *
	 * @param stmt
	 *            Statement to type check
	 * @param environment
	 *            Determines the type of all variables immediately going into this
	 *            block
	 * @return
	 */
	private Environment checkAssert(Stmt.Assert stmt, Environment environment, EnclosingScope scope) {
		return checkCondition(stmt.getCondition(), true, environment);
	}

	/**
	 * Type check an assume statement. This requires checking that the expression
	 * being assumed is well-formed and has boolean type. An assume statement can
	 * affect the resulting environment in certain cases, such as when a type test
	 * is assert. For example, after <code>assert x is int</code> the environment
	 * will regard <code>x</code> as having type <code>int</code>.
	 *
	 * @param stmt
	 *            Statement to type check
	 * @param environment
	 *            Determines the type of all variables immediately going into this
	 *            block
	 * @return
	 */
	private Environment checkAssume(Stmt.Assume stmt, Environment environment, EnclosingScope scope) {
		return checkCondition(stmt.getCondition(), true, environment);
	}

	/**
	 * Type check a fail statement. The environment after a fail statement is
	 * "bottom" because that represents an unreachable program point.
	 *
	 * @param stmt
	 *            Statement to type check
	 * @param environment
	 *            Determines the type of all variables immediately going into this
	 *            block
	 * @return
	 */
	private Environment checkFail(Stmt.Fail stmt, Environment environment, EnclosingScope scope) {
		return FlowTypeUtils.BOTTOM;
	}

	/**
	 * Type check a given sequence of variable declarations.
	 *
	 * @param decls
	 * @param environment
	 *            Determines the type of all variables immediately going into this
	 *            statement.
	 * @return
	 * @throws IOException
	 */
	private Environment checkVariableDeclarations(Tuple<Decl.Variable> decls, Environment environment) {
		for (int i = 0; i != decls.size(); ++i) {
			environment = checkVariableDeclaration(decls.get(i), environment);
		}
		return environment;
	}

	/**
	 * Type check a variable declaration statement. In particular, when an
	 * initialiser is given we must check it is well-formed and that it is a subtype
	 * of the declared type.
	 *
	 * @param decl
	 *            Statement to type check
	 * @param environment
	 *            Determines the type of all variables immediately going into this
	 *            block
	 * @return
	 */
	private Environment checkVariableDeclaration(Decl.Variable decl, Environment environment) {
		// Check type is sensible
		checkNonEmpty(decl, environment);
		// Check type of initialiser.
		if (decl.hasInitialiser()) {
			SemanticType type = checkExpression(decl.getInitialiser(), environment);
			checkIsSubtype(decl.getType(), type, environment, decl.getInitialiser());
		}
		// Done.
		return environment;
	}

	/**
	 * Type check an assignment statement.
	 *
	 * @param stmt
	 *            Statement to type check
	 * @param environment
	 *            Determines the type of all variables immediately going into this
	 *            block
	 * @return
	 */
	private Environment checkAssign(Stmt.Assign stmt, Environment environment, EnclosingScope scope)
			throws IOException {
		Tuple<LVal> lvals = stmt.getLeftHandSide();
		Type[] types = new Type[lvals.size()];
		for (int i = 0; i != lvals.size(); ++i) {
			types[i] = checkLVal(lvals.get(i), environment);
		}
		checkMultiExpressions(stmt.getRightHandSide(), environment, new Tuple<>(types));
		return environment;
	}

	/**
	 * Type check a break statement. This requires propagating the current
	 * environment to the block destination, to ensure that the actual types of all
	 * variables at that point are precise.
	 *
	 * @param stmt
	 *            Statement to type check
	 * @param environment
	 *            Determines the type of all variables immediately going into this
	 *            block
	 * @return
	 */
	private Environment checkBreak(Stmt.Break stmt, Environment environment, EnclosingScope scope) {
		// FIXME: need to check environment to the break destination
		return FlowTypeUtils.BOTTOM;
	}

	/**
	 * Type check a continue statement. This requires propagating the current
	 * environment to the block destination, to ensure that the actual types of all
	 * variables at that point are precise.
	 *
	 * @param stmt
	 *            Statement to type check
	 * @param environment
	 *            Determines the type of all variables immediately going into this
	 *            block
	 * @return
	 */
	private Environment checkContinue(Stmt.Continue stmt, Environment environment, EnclosingScope scope) {
		// FIXME: need to check environment to the continue destination
		return FlowTypeUtils.BOTTOM;
	}

	/**
	 * Type check an assume statement. This requires checking that the expression
	 * being printed is well-formed and has string type.
	 *
	 * @param stmt
	 *            Statement to type check
	 * @param environment
	 *            Determines the type of all variables immediately going into this
	 *            block
	 * @return
	 */
	private Environment checkDebug(Stmt.Debug stmt, Environment environment, EnclosingScope scope) {
		// FIXME: want to refine integer type here
		Type std_ascii = new Type.Array(Type.Int);
		SemanticType type = checkExpression(stmt.getOperand(), environment);
		checkIsSubtype(std_ascii, type, environment, stmt.getOperand());
		return environment;
	}

	/**
	 * Type check a do-while statement.
	 *
	 * @param stmt
	 *            Statement to type check
	 * @param environment
	 *            Determines the type of all variables immediately going into this
	 *            block
	 * @return
	 * @throws ResolveError
	 *             If a named type within this statement cannot be resolved within
	 *             the enclosing project.
	 */
	private Environment checkDoWhile(Stmt.DoWhile stmt, Environment environment, EnclosingScope scope) {
		// Type check loop body
		environment = checkBlock(stmt.getBody(), environment, scope);
		// Type check invariants
		checkConditions(stmt.getInvariant(), true, environment);
		// Determine and update modified variables
		Tuple<Decl.Variable> modified = FlowTypeUtils.determineModifiedVariables(stmt.getBody());
		stmt.setModified(stmt.getHeap().allocate(modified));
		// Type condition assuming its false to represent the terminated loop.
		// This is important if the condition contains a type test, as we'll
		// know that doesn't hold here.
		return checkCondition(stmt.getCondition(), false, environment);
	}

	/**
	 * Type check an if-statement. To do this, we check the environment through both
	 * sides of condition expression. Each can produce a different environment in
	 * the case that runtime type tests are used. These potentially updated
	 * environments are then passed through the true and false blocks which, in
	 * turn, produce updated environments. Finally, these two environments are
	 * joined back together. The following illustrates:
	 *
	 * <pre>
	 *                    //  Environment
	 * function f(int|null x) -> int:
	 *                    // {x : int|null}
	 *    if x is null:
	 *                    // {x : null}
	 *        return 0
	 *                    // {x : int}
	 *    else:
	 *                    // {x : int}
	 *        x = x + 1
	 *                    // {x : int}
	 *    // --------------------------------------------------
	 *                    // {x : int} o {x : int} => {x : int}
	 *    return x
	 * </pre>
	 *
	 * Here, we see that the type of <code>x</code> is initially
	 * <code>int|null</code> before the first statement of the function body. On the
	 * true branch of the type test this is updated to <code>null</code>, whilst on
	 * the false branch it is updated to <code>int</code>. Finally, the type of
	 * <code>x</code> at the end of each block is <code>int</code> and, hence, its
	 * type after the if-statement is <code>int</code>.
	 *
	 * @param stmt
	 *            Statement to type check
	 * @param environment
	 *            Determines the type of all variables immediately going into this
	 *            block
	 * @return
	 * @throws ResolveError
	 *             If a named type within this statement cannot be resolved within
	 *             the enclosing project.
	 */
	private Environment checkIfElse(Stmt.IfElse stmt, Environment environment, EnclosingScope scope) {
		// Check condition and apply variable retypings.
		Environment trueEnvironment = checkCondition(stmt.getCondition(), true, environment);
		Environment falseEnvironment = checkCondition(stmt.getCondition(), false, environment);
		// Update environments for true and false branches
		if (stmt.hasFalseBranch()) {
			trueEnvironment = checkBlock(stmt.getTrueBranch(), trueEnvironment, scope);
			falseEnvironment = checkBlock(stmt.getFalseBranch(), falseEnvironment, scope);
		} else {
			trueEnvironment = checkBlock(stmt.getTrueBranch(), trueEnvironment, scope);
		}
		// Join results back together
		return FlowTypeUtils.union(trueEnvironment, falseEnvironment);
	}

	/**
	 * Type check a <code>return</code> statement. If a return expression is given,
	 * then we must check that this is well-formed and is a subtype of the enclosing
	 * function or method's declared return type. The environment after a return
	 * statement is "bottom" because that represents an unreachable program point.
	 *
	 * @param stmt
	 *            Statement to type check
	 * @param environment
	 *            Determines the type of all variables immediately going into this
	 *            block
	 * @param scope
	 *            The stack of enclosing scopes
	 * @return
	 * @throws ResolveError
	 *             If a named type within this statement cannot be resolved within
	 *             the enclosing project.
	 */
	private Environment checkReturn(Stmt.Return stmt, Environment environment, EnclosingScope scope)
			throws IOException {
		// Determine the set of return types for the enclosing function or
		// method. This then allows us to check the given operands are
		// appropriate subtypes.
		Decl.FunctionOrMethod fm = scope.getEnclosingScope(FunctionOrMethodScope.class).getDeclaration();
		Tuple<Type> types = fm.getType().getReturns();
		// Type check the operands for the return statement (if any)
		checkMultiExpressions(stmt.getReturns(), environment, types);
		// Return bottom as following environment to signal that control-flow
		// cannot continue here. Thus, any following statements will encounter
		// the BOTTOM environment and, hence, report an appropriate error.
		return FlowTypeUtils.BOTTOM;
	}

	/**
	 * Type check a <code>skip</code> statement, which has no effect on the
	 * environment.
	 *
	 * @param stmt
	 *            Statement to type check
	 * @param environment
	 *            Determines the type of all variables immediately going into this
	 *            block
	 * @return
	 */
	private Environment checkSkip(Stmt.Skip stmt, Environment environment, EnclosingScope scope) {
		return environment;
	}

	/**
	 * Type check a <code>switch</code> statement. This is similar, in some ways, to
	 * the handling of if-statements except that we have n code blocks instead of
	 * just two. Therefore, we check type information through each block, which
	 * produces n potentially different environments and these are all joined
	 * together to produce the environment which holds after this statement. For
	 * example:
	 *
	 * <pre>
	 *                    //  Environment
	 * function f(int x) -> int|null:
	 *    int|null y
	 *                    // {x : int, y : void}
	 *    switch x:
	 *       case 0:
	 *                    // {x : int, y : void}
	 *           return 0
	 *                    // { }
	 *       case 1,2,3:
	 *                    // {x : int, y : void}
	 *           y = x
	 *                    // {x : int, y : int}
	 *       default:
	 *                    // {x : int, y : void}
	 *           y = null
	 *                    // {x : int, y : null}
	 *    // --------------------------------------------------
	 *                    // {} o
	 *                    // {x : int, y : int} o
	 *                    // {x : int, y : null}
	 *                    // => {x : int, y : int|null}
	 *    return y
	 * </pre>
	 *
	 * Here, the environment after the declaration of <code>y</code> has its actual
	 * type as <code>void</code> since no value has been assigned yet. For each of
	 * the case blocks, this initial environment is (separately) updated to produce
	 * three different environments. Finally, each of these is joined back together
	 * to produce the environment going into the <code>return</code> statement.
	 *
	 * @param stmt
	 *            Statement to type check
	 * @param environment
	 *            Determines the type of all variables immediately going into this
	 *            block
	 * @return
	 */
	private Environment checkSwitch(Stmt.Switch stmt, Environment environment, EnclosingScope scope)
			throws IOException {
		// Type check the expression being switched upon
		checkExpression(stmt.getCondition(), environment);
		// The final environment determines what flow continues after the switch
		// statement
		Environment finalEnv = null;
		// The record is whether a default case is given or not is important. If
		// not, then final environment always matches initial environment.
		boolean hasDefault = false;
		//
		for (Stmt.Case c : stmt.getCases()) {
			// Resolve the constants
			for (Expr e : c.getConditions()) {
				checkExpression(e, environment);
			}
			// Check case block
			Environment localEnv = environment;
			localEnv = checkBlock(c.getBlock(), localEnv, scope);
			// Merge resulting environment
			if (finalEnv == null) {
				finalEnv = localEnv;
			} else {
				finalEnv = FlowTypeUtils.union(finalEnv, localEnv);
			}
			// Keep track of whether a default
			hasDefault |= (c.getConditions().size() == 0);
		}

		if (!hasDefault) {
			// in this case, there is no default case in the switch. We must
			// therefore assume that there are values which will fall right
			// through the switch statement without hitting a case. Therefore,
			// we must include the original environment to accound for this.
			finalEnv = FlowTypeUtils.union(finalEnv, environment);
		}

		return finalEnv;
	}

	/**
	 * Type check a <code>NamedBlock</code> statement.
	 *
	 * @param stmt
	 *            Statement to type check
	 * @param environment
	 *            Determines the type of all variables immediately going into this
	 *            block
	 * @return
	 */
	private Environment checkNamedBlock(Stmt.NamedBlock stmt, Environment environment, EnclosingScope scope) {
		// Updated the environment with new within relations
		LifetimeDeclaration enclosing = scope.getEnclosingScope(LifetimeDeclaration.class);
		String[] lifetimes = enclosing.getDeclaredLifetimes();
		environment = environment.declareWithin(stmt.getName().get(), lifetimes);
		// Create an appropriate scope for this block
		scope = new NamedBlockScope(scope, stmt);
		return checkBlock(stmt.getBlock(), environment, scope);
	}

	/**
	 * Type check a <code>whiley</code> statement.
	 *
	 * @param stmt
	 *            Statement to type check
	 * @param environment
	 *            Determines the type of all variables immediately going into this
	 *            block
	 * @return
	 * @throws ResolveError
	 *             If a named type within this statement cannot be resolved within
	 *             the enclosing project.
	 */
	private Environment checkWhile(Stmt.While stmt, Environment environment, EnclosingScope scope) {
		// Type loop invariant(s).
		checkConditions(stmt.getInvariant(), true, environment);
		// Type condition assuming its true to represent inside a loop
		// iteration.
		// Important if condition contains a type test, as we'll know it holds.
		Environment trueEnvironment = checkCondition(stmt.getCondition(), true, environment);
		// Type condition assuming its false to represent the terminated loop.
		// Important if condition contains a type test, as we'll know it doesn't
		// hold.
		Environment falseEnvironment = checkCondition(stmt.getCondition(), false, environment);
		// Type loop body using true environment
		checkBlock(stmt.getBody(), trueEnvironment, scope);
		// Determine and update modified variables
		Tuple<Decl.Variable> modified = FlowTypeUtils.determineModifiedVariables(stmt.getBody());
		stmt.setModified(stmt.getHeap().allocate(modified));
		// Return false environment to represent flow after loop.
		return falseEnvironment;
	}

	// =========================================================================
	// Condition
	// =========================================================================

	/**
	 * Type check a sequence of zero or more conditions, such as the requires clause
	 * of a function or method. The environment from each condition is fed into the
	 * following. This means that, in principle, type tests influence both
	 * subsequent conditions and the remainder. The following illustrates:
	 *
	 * <pre>
	 * function f(int|null x) -> (int r)
	 * requires x is int
	 * requires x >= 0:
	 *    //
	 *    return x
	 * </pre>
	 *
	 * This type checks because of the initial type test <code>x is int</code>.
	 * Observe that, if the order of <code>requires</code> clauses was reversed,
	 * this would not type check. Finally, it is an interesting question as to why
	 * the above ever make sense. In general, it's better to simply declare
	 * <code>x</code> as type <code>int</code>. However, in some cases we may be
	 * unable to do this (for example, to preserve binary compatibility with a
	 * previous interface).
	 *
	 * @param conditions
	 * @param sign
	 * @param environment
	 * @return
	 */
	public Environment checkConditions(Tuple<Expr> conditions, boolean sign, Environment environment) {
		for (Expr e : conditions) {
			// Thread environment through from before
			environment = checkCondition(e, sign, environment);
		}
		return environment;
	}

	/**
	 * <p>
	 * Type check a given condition in a given environment with a given sign (which
	 * indicates whether the condition is known to hold or not). In certain
	 * situations (e.g. an if-statement) a condition may update the environment in
	 * accordance with any type tests used within. This is important to ensure that
	 * variables are <i>retyped</i> in e.g. if-statements. The simplest possible
	 * example is the following:
	 * </p>
	 *
	 * <pre>
	 * function f(int x) -> (int r):
	 *     if x &gt; 0:
	 *        return x + 1
	 *     else:
	 *        return 0
	 * </pre>
	 *
	 * <p>
	 * When (for example) typing <code>x &gt; 0</code> here, the environment would
	 * simply map <code>x</code> to its declared type <code>int</code>. However,
	 * because Whiley supports "flow typing", it's not always the case that the
	 * declared type of a variable is the right one to use. Consider a more complex
	 * case.
	 * </p>
	 *
	 * <pre>
	 * function g(int|null x) -> (int r):
	 *     if (x is int) && (x &gt; 0):
	 *        return x + 1
	 *     else:
	 *        return 0
	 * </pre>
	 *
	 * <p>
	 * This time, when typing (for example) typing <code>x &gt; 0</code>, we need to
	 * account for the fact that <code>x is int</code> is known. As such, the
	 * calculated type for <code>x</code> would be <code>(int|null)&int</code> when
	 * typing both <code>x &gt; 0</code> and <code>x + 1</code>.
	 * </p>
	 * <p>
	 * The purpose of the "sign" is to aid flow typing in the presence of negations.
	 * In essence, the sign indicates whether the statement being type checked is
	 * positive (i.e. sign=<code>true</code>) or negative (i.e.
	 * sign=<code>false</code>). In the latter case, the application of any type
	 * tests will be inverted. The following illustrates an interesting example:
	 * </p>
	 *
	 * <pre>
	 * function h(int|null x) -> (int r):
	 *     if !(x is null || x &lt; 0)
	 *        return x + 1
	 *     else:
	 *        return 0
	 * </pre>
	 *
	 * <p>
	 * To type check this example, the type checker needs to effectively "push" the
	 * logical negation through the disjunction to give
	 * <code>!(x is null) && x &gt;= 0</code>. The purpose of the sign is to enable
	 * this without actually rewriting the source code.
	 * </p>
	 *
	 * @param condition
	 *            The condition being type checked
	 * @param sign
	 *            The assumed outcome of the condition (either true or false).
	 * @param environment
	 *            The environment going into the condition
	 * @return The (potentially updated) typing environment for this statement.
	 */
	public Environment checkCondition(Expr condition, boolean sign, Environment environment) {
		switch (condition.getOpcode()) {
		case EXPR_logicalnot:
			return checkLogicalNegation((Expr.LogicalNot) condition, sign, environment);
		case EXPR_logicalor:
			return checkLogicalDisjunction((Expr.LogicalOr) condition, sign, environment);
		case EXPR_logicaland:
			return checkLogicalConjunction((Expr.LogicalAnd) condition, sign, environment);
		case EXPR_logicaliff:
			return checkLogicalIff((Expr.LogicalIff) condition, sign, environment);
		case EXPR_logiaclimplication:
			return checkLogicalImplication((Expr.LogicalImplication) condition, sign, environment);
		case EXPR_is:
			return checkIs((Expr.Is) condition, sign, environment);
		case EXPR_logicaluniversal:
		case EXPR_logicalexistential:
			return checkQuantifier((Expr.Quantifier) condition, sign, environment);
		default:
			SemanticType t = checkExpression(condition, environment);
			checkIsSubtype(Type.Bool, t, environment, condition);
			return environment;
		}
	}

	/**
	 * Type check a logical negation. This is relatively straightforward as we just
	 * flip the sign. Thus, if something is assumed to hold, then it is now assumed
	 * not to hold, etc. The following illustrates:
	 *
	 * <pre>
	 * function f(int|null x) -> (bool r):
	 *     return !(x is null) && x >= 0
	 * </pre>
	 *
	 * The effect of the negation <code>!(x is null)</code> is that the type test is
	 * now evaluated assuming it fails. Thus, it effects the environment by
	 * asserting <code>x</code> has type <code>(int|null)&!null</code> which is
	 * equivalent to <code>int</code>.
	 *
	 * @param expr
	 * @param sign
	 * @param environment
	 * @return
	 */
	private Environment checkLogicalNegation(Expr.LogicalNot expr, boolean sign, Environment environment) {
		return checkCondition(expr.getOperand(), !sign, environment);
	}

	/**
	 * In this case, we are assuming the environments are exclusive from each other
	 * (i.e. this is the opposite of threading them through). For example, consider
	 * this case:
	 *
	 * <pre>
	 * function f(int|null x) -> (bool r):
	 *   return (x is null) || (x >= 0)
	 * </pre>
	 *
	 * The environment produced by the left condition is <code>{x->null}</code>. We
	 * cannot thread this environment into the right condition as, clearly, it's not
	 * correct. Instead, we want to thread through the environment which arises on
	 * the assumption the fist case is false. That would be <code>{x->!null}</code>.
	 * Finally, the resulting environment is simply the union of the two
	 * environments from each case.
	 *
	 * @param operands
	 * @param sign
	 * @param environment
	 *
	 * @return
	 */
	private Environment checkLogicalDisjunction(Expr.LogicalOr expr, boolean sign, Environment environment) {
		Tuple<Expr> operands = expr.getOperands();
		if (sign) {
			Environment[] refinements = new Environment[operands.size()];
			for (int i = 0; i != operands.size(); ++i) {
				refinements[i] = checkCondition(operands.get(i), sign, environment);
				// The clever bit. Recalculate assuming opposite sign.
				environment = checkCondition(operands.get(i), !sign, environment);
			}
			// Done.
			return FlowTypeUtils.union(refinements);
		} else {
			for (int i = 0; i != operands.size(); ++i) {
				environment = checkCondition(operands.get(i), sign, environment);
			}
			return environment;
		}
	}

	/**
	 * In this case, we are threading each environment as is through to the next
	 * statement. For example, consider this example:
	 *
	 * <pre>
	 * function f(int|null x) -> (bool r):
	 *   return (x is int) && (x >= 0)
	 * </pre>
	 *
	 * The environment going into <code>x is int</code> will be
	 * <code>{x->(int|null)}</code>. The environment coming out of this statement
	 * will be <code>{x-&gt;int}</code> and this is just threaded directly into the
	 * next statement <code>x &gt; 0</code>
	 *
	 * @param operands
	 * @param sign
	 * @param environment
	 *
	 * @return
	 */
	private Environment checkLogicalConjunction(Expr.LogicalAnd expr, boolean sign, Environment environment) {
		Tuple<Expr> operands = expr.getOperands();
		if (sign) {
			for (int i = 0; i != operands.size(); ++i) {
				environment = checkCondition(operands.get(i), sign, environment);
			}
			return environment;
		} else {
			Environment[] refinements = new Environment[operands.size()];
			for (int i = 0; i != operands.size(); ++i) {
				refinements[i] = checkCondition(operands.get(i), sign, environment);
				// The clever bit. Recalculate assuming opposite sign.
				environment = checkCondition(operands.get(i), !sign, environment);
			}
			// Done.
			return FlowTypeUtils.union(refinements);
		}
	}

	private Environment checkLogicalImplication(Expr.LogicalImplication expr, boolean sign, Environment environment) {
		// To understand this, remember that A ==> B is equivalent to !A || B.
		if (sign) {
			// First case assumes the if body doesn't hold.
			Environment left = checkCondition(expr.getFirstOperand(), false, environment);
			// Second case assumes the if body holds ...
			environment = checkCondition(expr.getFirstOperand(), true, environment);
			// ... and then passes this into the then body
			Environment right = checkCondition(expr.getSecondOperand(), true, environment);
			//
			return FlowTypeUtils.union(left, right);
		} else {
			// Effectively, this is a conjunction equivalent to A && !B
			environment = checkCondition(expr.getFirstOperand(), true, environment);
			environment = checkCondition(expr.getSecondOperand(), false, environment);
			return environment;
		}
	}

	private Environment checkLogicalIff(Expr.LogicalIff expr, boolean sign, Environment environment) {
		environment = checkCondition(expr.getFirstOperand(), sign, environment);
		environment = checkCondition(expr.getSecondOperand(), sign, environment);
		return environment;
	}

	private Environment checkIs(Expr.Is expr, boolean sign, Environment environment) {
		Expr lhs = expr.getOperand();
		SemanticType lhsT = checkExpression(expr.getOperand(), environment);
		SemanticType rhsT = expr.getTestType();
		// Sanity check operands for this type test
		SemanticType trueBranchRefinementT = new SemanticType.Intersection(lhsT, rhsT);
		SemanticType falseBranchRefinementT = new SemanticType.Difference(lhsT, rhsT);
		//
		// NOTE: it's a little unclear to me whether use the strict subtype operator
		// here makes sense in the long run. However, using the relaxed subtype operator
		// definitely results in problems! See #845
		if (strictSubtypeOperator.isVoid(trueBranchRefinementT, environment)) {
			// DEFINITE TRUE CASE
			syntaxError(expr, INCOMPARABLE_OPERANDS, lhsT, rhsT);
		} else if (strictSubtypeOperator.isVoid(falseBranchRefinementT, environment)) {
			// DEFINITE FALSE CASE
			syntaxError(expr, BRANCH_ALWAYS_TAKEN);
		}
		//
		Pair<Decl.Variable, Type> extraction = FlowTypeUtils.extractTypeTest(lhs, expr.getTestType());
		if (extraction != null) {
			Decl.Variable var = extraction.getFirst();
			SemanticType varT = environment.getType(var);
			SemanticType refinementT = extraction.getSecond();
			if (sign) {
				refinementT = new Type.Intersection(varT, refinementT);
			} else {
				refinementT = new SemanticType.Difference(varT, refinementT);
			}
			// Update the typing environment accordingly.
			environment = environment.refineType(var, refinementT);
		}
		//
		return environment;
	}

	private Environment checkQuantifier(Expr.Quantifier stmt, boolean sign, Environment environment) {
		checkNonEmpty(stmt.getParameters(), environment);
		// Check array initialisers
		for (Decl.Variable decl : stmt.getParameters()) {
			checkExpression(decl.getInitialiser(), environment);
		}
		// NOTE: We throw away the returned environment from the body. This is
		// because any type tests within the body are ignored outside.
		checkCondition(stmt.getOperand(), true, environment);
		//
		return environment;
	}

	// =========================================================================
	// LVals
	// =========================================================================

	/**
	 * Type check a given lval assuming an initial environment. This returns the
	 * largest type which can be safely assigned to the lval. Observe that this type
	 * is determined by the declared type of the variable being assigned.
	 *
	 * @param expression
	 * @param environment
	 * @return
	 * @throws ResolutionError
	 */
	public Type checkLVal(LVal lval, Environment environment) {
		Type type;
		switch (lval.getOpcode()) {
		case EXPR_variablecopy:
			type = checkVariableLVal((Expr.VariableAccess) lval, environment);
			break;
		case EXPR_staticvariable:
			type = checkStaticVariableLVal((Expr.StaticVariableAccess) lval, environment);
			break;
		case EXPR_arrayaccess:
		case EXPR_arrayborrow:
			type = checkArrayLVal((Expr.ArrayAccess) lval, environment);
			break;
		case EXPR_recordaccess:
		case EXPR_recordborrow:
			type = checkRecordLVal((Expr.RecordAccess) lval, environment);
			break;
		case EXPR_dereference:
			type = checkDereferenceLVal((Expr.Dereference) lval, environment);
			break;
		default:
			return internalFailure("unknown lval encountered (" + lval.getClass().getSimpleName() + ")", lval);
		}
		// Sanity check type. This can be non-sensical in the case of an upsteam type
		// error
		if (type != null) {
			lval.setType(lval.getHeap().allocate(type));
		}
		return type;
	}

	public Type checkVariableLVal(Expr.VariableAccess lval, Environment environment) {
		// At this point, we return the declared type of the variable rather
		// than the potentially refined type held in the environment. This
		// is critical as, otherwise, the current refinement would
		// unnecessarily restrict what we could assign to this variable.
		return lval.getVariableDeclaration().getType();
	}

	public Type checkStaticVariableLVal(Expr.StaticVariableAccess lval, Environment environment) {
		Decl.Link<Decl.StaticVariable> l = lval.getLink();
		// Check whether this access was successfully resolved or not.
		if (l.isResolved()) {
			// Yes, it was resolved so proceed as normal
			return l.getTarget().getType();
		} else {
			// No, it wasn't resolved so proceed with dummy
			return null;
		}
	}

	public Type checkArrayLVal(Expr.ArrayAccess lval, Environment environment) {
		SemanticType src = checkExpression(lval.getFirstOperand(), environment);
		// Extract the determined array type
		SemanticType.Array arrT = extractArrayType(src, environment, ReadWriteTypeExtractor.WRITEABLE_ARRAY,
				lval.getFirstOperand());
		// Sanity check extraction
		if(arrT != null) {
			// Check for integer subscript
			SemanticType subscriptT = checkExpression(lval.getSecondOperand(), environment);
			checkIsSubtype(Type.Int, subscriptT, environment, lval.getSecondOperand());
			// Extract element type
			SemanticType elementT = extractElementType(arrT, lval.getFirstOperand());
			// Convert to concrete type
			return concreteTypeExtractor.apply(arrT.getElement(), environment);
		} else {
			// typing failure upstream
			return null;
		}
	}

	public Type checkRecordLVal(Expr.RecordAccess lval, Environment environment) {
		SemanticType src = checkExpression(lval.getOperand(), environment);
		// Extract writeable record type
		SemanticType.Record recT = extractRecordType(src, environment, ReadWriteTypeExtractor.WRITEABLE_RECORD,
				lval.getOperand());
		// Extract the field type
		return extractConcreteFieldType(recT, lval.getField(), environment);
	}

	public Type checkDereferenceLVal(Expr.Dereference lval, Environment environment) {
		SemanticType src = checkExpression(lval.getOperand(), environment);
		// Extract writeable reference type
		SemanticType.Reference refT = extractReferenceType(src, environment, ReadWriteTypeExtractor.WRITEABLE_REFERENCE,
				lval.getOperand());
		// Sanity check extraction
		if(refT != null) {
			// Extract element type
			SemanticType elementT = extractElementType(refT, lval.getOperand());
			//
			return concreteTypeExtractor.apply(elementT, environment);
		} else {
			// typing failure upstream
			return null;
		}
	}

	// =========================================================================
	// Expressions
	// =========================================================================

	/**
	 * Type check a sequence of zero or more multi-expressions, assuming a given
	 * initial environment. A multi-expression is one which may have multiple return
	 * values. There are relatively few situations where this can arise, particular
	 * assignments and return statements. This returns a sequence of one or more
	 * pairs, each of which corresponds to a single return for a given expression.
	 * Thus, each expression generates one or more pairs in the result.
	 *
	 * @param expressions
	 * @param environment
	 */
	public final void checkMultiExpressions(Tuple<Expr> expressions, Environment environment, Tuple<Type> expected) {
		for (int i = 0, j = 0; i != expressions.size(); ++i) {
			Expr expression = expressions.get(i);
			switch (expression.getOpcode()) {
			case EXPR_invoke: {
				Tuple<Type> results = checkInvoke((Expr.Invoke) expression, environment);
				if (results == null) {
					// Some type error occurred upstream, therefore make conservative assumption
					j = j + 1;
				} else {
					// FIXME: THIS LOOP IS UGLY
					for (int k = 0; k != results.size(); ++k) {
						checkIsSubtype(expected.get(j + k), results.get(k), environment, expression);
					}
					j = j + results.size();
				}
				break;
			}
			case EXPR_indirectinvoke: {
				Tuple<Type> results = checkIndirectInvoke((Expr.IndirectInvoke) expression, environment);
				if (results == null) {
					// Some type error occurred upstream, therefore make conservative assumption
					j = j + 1;
				} else {
					// FIXME: THIS LOOP IS UGLY
					for (int k = 0; k != results.size(); ++k) {
						checkIsSubtype(expected.get(j + k), results.get(k), environment, expression);
					}
					j = j + results.size();
				}
				break;
			}
			default:
				SemanticType type = checkExpression(expression, environment);
				//
				if ((expected.size() - j) < 1) {
					syntaxError(expression, TOO_MANY_RETURNS);
				} else if ((i + 1) == expressions.size() && (expected.size() - j) > 1) {
					syntaxError(expression, INSUFFICIENT_RETURNS);
				} else {
					checkIsSubtype(expected.get(j), type, environment, expression);
				}
				j = j + 1;
			}
		}
	}

	/**
	 * Type check a given expression assuming an initial environment.
	 *
	 * @param expression
	 *            The expression to be checked.
	 * @param target
	 *            The target type of this expression.
	 * @param environment
	 *            The environment in which this expression is to be typed
	 * @return
	 * @throws ResolutionError
	 */
	public SemanticType checkExpression(Expr expression, Environment environment) {
		SemanticType type;

		switch (expression.getOpcode()) {
		case EXPR_constant:
			type = checkConstant((Expr.Constant) expression, environment);
			break;
		case EXPR_variablecopy:
			type = checkVariable((Expr.VariableAccess) expression, environment);
			break;
		case EXPR_staticvariable:
			type = checkStaticVariable((Expr.StaticVariableAccess) expression, environment);
			break;
		case EXPR_cast:
			type = checkCast((Expr.Cast) expression, environment);
			break;
		case EXPR_invoke: {
			Tuple<Type> types = checkInvoke((Expr.Invoke) expression, environment);
			// Sanity check
			if (types == null) {
				// Type error occurred upstream, therefore propagate up.
				return null;
			} else {
				switch (types.size()) {
				case 0:
					syntaxError(expression, INSUFFICIENT_RETURNS);
					return null;
				case 1:
					break;
				default:
					syntaxError(expression, TOO_MANY_RETURNS);
				}
				return types.get(0);
			}
		}
		case EXPR_indirectinvoke: {
			Tuple<Type> types = checkIndirectInvoke((Expr.IndirectInvoke) expression, environment);
			// Sanity check
			if (types == null) {
				// Type error occurred upstream, therefore propagate up.
				return null;
			} else {
				switch (types.size()) {
				case 0:
					syntaxError(expression, TOO_MANY_RETURNS);
				case 1:
					break;
				default:
					syntaxError(expression, INSUFFICIENT_RETURNS);
				}
				// NOTE: can return directly here as checkIndirectInvoke must already set the
				// return types.
				return types.get(0);
			}
		}
		// Conditions
		case EXPR_logicalnot:
		case EXPR_logicalor:
		case EXPR_logicaland:
		case EXPR_logicaliff:
		case EXPR_logiaclimplication:
		case EXPR_is:
		case EXPR_logicaluniversal:
		case EXPR_logicalexistential:
			checkCondition(expression, true, environment);
			return Type.Bool;
		// Comparators
		case EXPR_equal:
		case EXPR_notequal:
		case EXPR_integerlessthan:
		case EXPR_integerlessequal:
		case EXPR_integergreaterthan:
		case EXPR_integergreaterequal:
			return checkComparisonOperator((Expr.BinaryOperator) expression, environment);
		// Arithmetic Operators
		case EXPR_integernegation:
			type = checkIntegerOperator((Expr.UnaryOperator) expression, environment);
			break;
		case EXPR_integeraddition:
		case EXPR_integersubtraction:
		case EXPR_integermultiplication:
		case EXPR_integerdivision:
		case EXPR_integerremainder:
			type = checkIntegerOperator((Expr.BinaryOperator) expression, environment);
			break;
		// Bitwise expressions
		case EXPR_bitwisenot:
			type = checkBitwiseOperator((Expr.UnaryOperator) expression, environment);
			break;
		case EXPR_bitwiseand:
		case EXPR_bitwiseor:
		case EXPR_bitwisexor:
			type = checkBitwiseOperator((Expr.NaryOperator) expression, environment);
			break;
		case EXPR_bitwiseshl:
		case EXPR_bitwiseshr:
			type = checkBitwiseShift((Expr.BinaryOperator) expression, environment);
			break;
		// Record Expressions
		case EXPR_recordinitialiser:
			type = checkRecordInitialiser((Expr.RecordInitialiser) expression, environment);
			break;
		case EXPR_recordaccess:
		case EXPR_recordborrow:
			type = checkRecordAccess((Expr.RecordAccess) expression, environment);
			break;
		case EXPR_recordupdate:
			type = checkRecordUpdate((Expr.RecordUpdate) expression, environment);
			break;
		// Array expressions
		case EXPR_arraylength:
			type = checkArrayLength((Expr.ArrayLength) expression, environment);
			break;
		case EXPR_arrayinitialiser:
			type = checkArrayInitialiser((Expr.ArrayInitialiser) expression, environment);
			break;
		case EXPR_arraygenerator:
			type = checkArrayGenerator((Expr.ArrayGenerator) expression, environment);
			break;
		case EXPR_arrayaccess:
		case EXPR_arrayborrow:
			type = checkArrayAccess((Expr.ArrayAccess) expression, environment);
			break;
		case EXPR_arrayrange:
			type = checkArrayRange((Expr.ArrayRange) expression, environment);
			break;
		case EXPR_arrayupdate:
			type = checkArrayUpdate((Expr.ArrayUpdate) expression, environment);
			break;
		// Reference expressions
		case EXPR_dereference:
			type = checkDereference((Expr.Dereference) expression, environment);
			break;
		case EXPR_new:
			type = checkNew((Expr.New) expression, environment);
			break;
		case EXPR_lambdaaccess:
			return checkLambdaAccess((Expr.LambdaAccess) expression, environment);
		case DECL_lambda:
			return checkLambdaDeclaration((Decl.Lambda) expression, environment);
		default:
			return internalFailure("unknown expression encountered (" + expression.getClass().getSimpleName() + ")",
					expression);
		}
		// Sanity check sensible type generated from expression. Something non-sensical
		// can be generated when there is a type error upsteam.
		if (type != null) {
			// Allocate and set type for expression
			Type concreteType = concreteTypeExtractor.apply(type, environment);
			// Sanity check output
			if (concreteType instanceof Type.Void) {
				// Something has definitely gone wrong in the type extraction process.
				syntaxError(expression, EMPTY_TYPE);
			} else {
				expression.setType(expression.getHeap().allocate(concreteType));
			}
		}
		// Done
		return type;
	}

	public Tuple<Type>[] toTupleTypes(Type[] expected) {
		Tuple<Type>[] tupleTypes = new Tuple[expected.length];
		for (int i = 0; i != expected.length; ++i) {
			tupleTypes[i] = new Tuple<>(expected[i]);
		}
		return tupleTypes;
	}

	/**
	 * Check the type of a given constant expression. This is straightforward since
	 * the determine is fully determined by the kind of constant we have.
	 *
	 * @param expr
	 * @return
	 */
	private SemanticType checkConstant(Expr.Constant expr, Environment env) {
		Value item = expr.getValue();
		switch (item.getOpcode()) {
		case ITEM_null:
			return Type.Null;
		case ITEM_bool:
			return Type.Bool;
		case ITEM_int:
			return Type.Int;
		case ITEM_byte:
			return Type.Byte;
		case ITEM_utf8:
			// FIXME: this is not an optimal solution. The reason being that we
			// have lost nominal information regarding whether it is an instance
			// of std::ascii or std::utf8, for example.
			return new SemanticType.Array(Type.Int);
		default:
			return internalFailure("unknown constant encountered: " + expr, expr);
		}
	}

	/**
	 * Check the type of a given variable access. This is straightforward since the
	 * determine is fully determined by the declared type for the variable in
	 * question.
	 *
	 * @param expr
	 * @return
	 */
	private SemanticType checkVariable(Expr.VariableAccess expr, Environment environment) {
		Decl.Variable var = expr.getVariableDeclaration();
		return environment.getType(var);
	}

	private SemanticType checkStaticVariable(Expr.StaticVariableAccess expr, Environment env) {
		//
		Decl.Link<Decl.StaticVariable> l = expr.getLink();
		if (l.isResolved()) {
			return l.getTarget().getType();
		} else {
			// Static variable access was not resolved during name resolution. Therefore,
			// there's nothing we can at this stage.
			return null;
		}
	}

	private SemanticType checkCast(Expr.Cast expr, Environment environment) {
		SemanticType rhsT = checkExpression(expr.getOperand(), environment);
		checkIsSubtype(expr.getType(), rhsT, environment, expr);
		return expr.getType();
	}

	private Tuple<Type> checkInvoke(Expr.Invoke expr, Environment environment) {
		boolean resolvable = true;
		Tuple<Expr> arguments = expr.getOperands();
		SemanticType[] types = new SemanticType[arguments.size()];
		for (int i = 0; i != arguments.size(); ++i) {
			SemanticType t = checkExpression(arguments.get(i), environment);
			resolvable &= (t != null);
			types[i] = t;
		}
		//
		Decl.Link<Decl.Callable> link = expr.getLink();
		// Attempt to resolve this invocation (if we can).
		if(link.isPartiallyResolved() && resolvable) {
			// Apply type inference algorithm to resolve invocation
			Type.Callable type = typeInference.bind(expr.getBinding(), new Tuple<>(types), environment);
			// Sanity check we found something
			if (type != null) {
				// Finally, return the declared returns
				return type.getReturns();
			}
			// failed
			syntaxError(link.getName(), AMBIGUOUS_CALLABLE, link.getCandidates());
		}
		return null;
	}

	private Tuple<Type> checkIndirectInvoke(Expr.IndirectInvoke expr, Environment environment) {
		// Determine signature type from source
		SemanticType type = checkExpression(expr.getSource(), environment);
		// Extract readable callable type
		Type.Callable sig = extractLambdaType(type, environment, ReadWriteTypeExtractor.READABLE_CALLABLE,
				expr.getSource());
		// Determine the argument types
		if (sig == null) {
			// Some kind of type error occurred upstream, therefore we're aborting.
			return null;
		} else {
			Tuple<Expr> arguments = expr.getArguments();
			Tuple<Type> parameters = sig.getParameters();
			// Sanity check number of arguments provided
			if (parameters.size() != arguments.size()) {
				syntaxError(expr, INSUFFICIENT_ARGUMENTS);
			}
			// Sanity check types of arguments provided
			for (int i = 0; i != arguments.size(); ++i) {
				// Determine argument type
				SemanticType arg = checkExpression(arguments.get(i), environment);
				// Check argument is subtype of parameter
				checkIsSubtype(parameters.get(i), arg, environment, arguments.get(i));
			}
			//
			if (sig.getReturns().size() > 1) {
				internalFailure("need support for multiple returns and indirect invocation", expr);
			}
			expr.setType(sig.getReturns().get(0));
			//
			return sig.getReturns();
		}
	}

	private SemanticType checkComparisonOperator(Expr.BinaryOperator expr, Environment environment) {
		switch (expr.getOpcode()) {
		case EXPR_equal:
		case EXPR_notequal:
			return checkEqualityOperator(expr, environment);
		default:
			return checkIntegerComparator(expr, environment);
		}
	}

	private SemanticType checkEqualityOperator(Expr.BinaryOperator expr, Environment environment) {
		SemanticType lhs = checkExpression(expr.getFirstOperand(), environment);
		SemanticType rhs = checkExpression(expr.getSecondOperand(), environment);
		if (lhs != null && rhs != null) {
			// Sanity check that the types of operands are actually comparable.
			SemanticType glb = new SemanticType.Intersection(lhs, rhs);
			if (strictSubtypeOperator.isVoid(glb, environment)) {
				syntaxError(expr, INCOMPARABLE_OPERANDS, lhs, rhs);
			}
		}
		return Type.Bool;
	}

	private SemanticType checkIntegerComparator(Expr.BinaryOperator expr, Environment environment) {
		checkOperand(Type.Int, expr.getFirstOperand(), environment);
		checkOperand(Type.Int, expr.getSecondOperand(), environment);
		return Type.Bool;
	}

	private SemanticType checkIntegerOperator(Expr.UnaryOperator expr, Environment environment) {
		checkOperand(Type.Int, expr.getOperand(), environment);
		return Type.Int;
	}

	/**
	 * Check the type for a given arithmetic operator. Such an operator has the type
	 * int, and all children should also produce values of type int.
	 *
	 * @param expr
	 * @return
	 */
	private SemanticType checkIntegerOperator(Expr.BinaryOperator expr, Environment environment) {
		checkOperand(Type.Int, expr.getFirstOperand(), environment);
		checkOperand(Type.Int, expr.getSecondOperand(), environment);
		return Type.Int;
	}

	private SemanticType checkBitwiseOperator(Expr.UnaryOperator expr, Environment environment) {
		checkOperand(Type.Byte, expr.getOperand(), environment);
		return Type.Byte;
	}

	private SemanticType checkBitwiseOperator(Expr.NaryOperator expr, Environment environment) {
		checkOperands(Type.Byte, expr.getOperands(), environment);
		return Type.Byte;
	}

	private SemanticType checkBitwiseShift(Expr.BinaryOperator expr, Environment environment) {
		checkOperand(Type.Byte, expr.getFirstOperand(), environment);
		checkOperand(Type.Int, expr.getSecondOperand(), environment);
		return Type.Byte;
	}

	private SemanticType checkRecordAccess(Expr.RecordAccess expr, Environment environment) {
		// Check expression against expected record types
		SemanticType src = checkExpression(expr.getOperand(), environment);
		// Following may produce null if field not present
		SemanticType.Record type = extractRecordType(src, environment, ReadWriteTypeExtractor.READABLE_RECORD,
				expr.getOperand());
		//
		// Return extracted field type
		return extractFieldType(type, expr.getField());
	}

	private SemanticType checkRecordUpdate(Expr.RecordUpdate expr, Environment environment) {
		// Check src and value expressions
		SemanticType src = checkExpression(expr.getFirstOperand(), environment);
		SemanticType val = checkExpression(expr.getSecondOperand(), environment);
		// Extract readable record type
		SemanticType.Record readableRecordT = extractRecordType(src, environment,
				ReadWriteTypeExtractor.READABLE_RECORD, expr.getFirstOperand());
		// Extract corresponding field type
		SemanticType fieldType = extractFieldType(readableRecordT, expr.getField());
		// Matched the field type
		checkIsSubtype(fieldType, val, environment, expr.getSecondOperand());
		return src;
	}

	private SemanticType checkRecordInitialiser(Expr.RecordInitialiser expr, Environment environment) {
		Tuple<Identifier> fields = expr.getFields();
		Tuple<Expr> operands = expr.getOperands();
		//
		SemanticType.Field[] decls = new SemanticType.Field[operands.size()];
		// Check field initialiser expressions one by one
		for (int i = 0; i != operands.size(); ++i) {
			Identifier field = fields.get(i);
			SemanticType fieldType = checkExpression(operands.get(i), environment);
			decls[i] = new SemanticType.Field(field, fieldType);
		}
		//
		return new SemanticType.Record(false, new Tuple<>(decls));
	}

	private SemanticType checkArrayLength(Expr.ArrayLength expr, Environment environment) {
		SemanticType sourceT = checkExpression(expr.getOperand(), environment);
		// Check whether the source returns an array type or not.
		extractArrayType(sourceT, environment, ReadWriteTypeExtractor.READABLE_ARRAY, expr.getOperand());
		//
		return Type.Int;
	}

	private SemanticType checkArrayInitialiser(Expr.ArrayInitialiser expr, Environment environment) {
		// Check initialiser expressions
		Tuple<Expr> operands = expr.getOperands();
		SemanticType[] ts = new SemanticType[operands.size()];
		for (int i = 0; i != ts.length; ++i) {
			ts[i] = checkExpression(operands.get(i), environment);
		}
		ts = ArrayUtils.removeDuplicates(ts);
		SemanticType element;
		switch (ts.length) {
		case 0:
			element = Type.Void;
			break;
		case 1:
			element = ts[0];
			break;
		default: {
			element = new SemanticType.Union(ts);
		}
		}
		return new SemanticType.Array(element);
	}

	private SemanticType checkArrayGenerator(Expr.ArrayGenerator expr, Environment environment) {
		Expr value = expr.getFirstOperand();
		Expr length = expr.getSecondOperand();
		// Check generation operands
		SemanticType valueT = checkExpression(value, environment);
		checkOperand(Type.Int, length, environment);
		//
		return new SemanticType.Array(valueT);
	}

	private SemanticType checkArrayAccess(Expr.ArrayAccess expr, Environment environment) {
		Expr source = expr.getFirstOperand();
		Expr subscript = expr.getSecondOperand();
		//
		SemanticType sourceT = checkExpression(source, environment);
		SemanticType subscriptT = checkExpression(subscript, environment);
		// Check whether source operand yielded an array type
		SemanticType.Array sourceArrayT = extractArrayType(sourceT, environment, ReadWriteTypeExtractor.READABLE_ARRAY,
				source);
		// Check subscript has int type
		checkIsSubtype(Type.Int, subscriptT, environment, subscript);
		// Return extracted array element type
		return extractElementType(sourceArrayT, expr.getFirstOperand());
	}

	private SemanticType checkArrayRange(Expr.ArrayRange expr, Environment environment) {
		SemanticType lhsT = checkExpression(expr.getFirstOperand(), environment);
		SemanticType rhsT = checkExpression(expr.getSecondOperand(), environment);
		// Check integer types
		checkIsSubtype(Type.Int, lhsT, environment, expr.getFirstOperand());
		checkIsSubtype(Type.Int, rhsT, environment, expr.getSecondOperand());
		// FIXME: what if lhsT and rhsT differ?
		return new SemanticType.Array(lhsT);
	}

	private SemanticType checkArrayUpdate(Expr.ArrayUpdate expr, Environment environment) {
		Expr source = expr.getFirstOperand();
		Expr subscript = expr.getSecondOperand();
		Expr value = expr.getThirdOperand();
		// Check operand expressions
		SemanticType sourceT = checkExpression(source, environment);
		SemanticType subscriptT = checkExpression(subscript, environment);
		SemanticType valueT = checkExpression(value, environment);
		// Extract the determined array type
		SemanticType.Array sourceArrayT = extractArrayType(sourceT, environment, ReadWriteTypeExtractor.READABLE_ARRAY,
				source);
		// Extract the array element type
		SemanticType elementT = extractElementType(sourceArrayT, expr.getFirstOperand());
		// Check for integer subscript
		checkIsSubtype(Type.Int, subscriptT, environment, subscript);
		checkIsSubtype(elementT, valueT, environment, value);
		return sourceArrayT;
	}

	private SemanticType checkDereference(Expr.Dereference expr, Environment environment) {
		SemanticType operandT = checkExpression(expr.getOperand(), environment);
		// Extract an appropriate reference type form the source.
		SemanticType.Reference readableReferenceT = extractReferenceType(operandT, environment,
				ReadWriteTypeExtractor.READABLE_REFERENCE, expr.getOperand());
		//
		return extractElementType(readableReferenceT, expr.getOperand());
	}

	private SemanticType checkNew(Expr.New expr, Environment environment) {
		// Check expression type against expected element types
		SemanticType operandT = checkExpression(expr.getOperand(), environment);
		//
		if (expr.hasLifetime()) {
			return new SemanticType.Reference(operandT, expr.getLifetime());
		} else {
			return new SemanticType.Reference(operandT);
		}
	}

	private SemanticType checkLambdaAccess(Expr.LambdaAccess expr, Environment environment) {
		Decl.Link<Decl.Callable> link = expr.getLink();
		Tuple<Type> types = expr.getParameterTypes();
		// FIXME: there is a problem here in that we cannot distinguish
		// between the case where no parameters were supplied and when
		// exactly zero arguments were supplied.
		if (types.size() > 0) {
			// Now attempt to bind the given candidate declarations against the concrete
			// argument types.
			return typeInference.bind(expr.getBinding(), types, environment);
		} else if (link.isResolved()) {
			return link.getTarget().getType();
		} else {
			//
			syntaxError(link.getName(), AMBIGUOUS_CALLABLE, link.getCandidates());
			return null;
		}
	}

	private SemanticType checkLambdaDeclaration(Decl.Lambda expr, Environment environment) {
		Tuple<Decl.Variable> parameters = expr.getParameters();
		Tuple<Type> parameterTypes = parameters.map((Decl.Variable p) -> p.getType());
		// Sanity check no parameter is "empty", that is equivalent to void.
		checkNonEmpty(parameters, environment);
		// Type check the body of the lambda using the expected return types
		Type result = concreteTypeExtractor.apply(checkExpression(expr.getBody(), environment), environment);
		// Determine whether or not this is a pure or impure lambda.
		Type.Callable signature;
		if (FlowTypeUtils.isPure(expr.getBody())) {
			// This is a pure lambda, hence it has function type.
			signature = new Type.Function(parameterTypes, new Tuple<>(result));
		} else {
			// This is an impure lambda, hence it has method type.
			signature = new Type.Method(parameterTypes, new Tuple<>(result), expr.getCapturedLifetimes(),
					expr.getLifetimes());
		}
		// Update lambda declaration with inferred signature.
		expr.setType(expr.getHeap().allocate(signature));
		// Done
		return signature;
	}

	// ==========================================================================
	// Helpers
	// ==========================================================================

	private void checkOperand(Type type, Expr operand, Environment environment) {
		checkIsSubtype(type, checkExpression(operand, environment), environment, operand);
	}

	private void checkOperands(Type type, Tuple<Expr> operands, Environment environment) {
		for (int i = 0; i != operands.size(); ++i) {
			Expr operand = operands.get(i);
			checkOperand(type, operand, environment);
		}
	}

	private void checkIsSubtype(SemanticType lhs, SemanticType rhs, LifetimeRelation lifetimes, SyntacticItem element) {
		if (lhs == null || rhs == null) {
			// A type error of some kind has occurred which has produced null instead of a
			// type. At this point, we proceed assuming everything is hunky dory untill we
			// can categorically find another problem.
		} else if (!relaxedSubtypeOperator.isSubtype(lhs, rhs, lifetimes)) {
			syntaxError(element, SUBTYPE_ERROR, lhs, rhs);
		}
	}

	private void checkContractive(Decl.Type d) {
		if (!relaxedSubtypeOperator.isContractive(d.getQualifiedName(), d.getType())) {
			syntaxError(d.getName(), EMPTY_TYPE);
		}
	}

	/**
	 * Check a given set of variable declarations are not "empty". That is, their
	 * declared type is not equivalent to void.
	 *
	 * @param decls
	 */
	private void checkNonEmpty(Tuple<Decl.Variable> decls, LifetimeRelation lifetimes) {
		for (int i = 0; i != decls.size(); ++i) {
			checkNonEmpty(decls.get(i), lifetimes);
		}
	}

	/**
	 * Check that a given variable declaration is not empty. That is, the declared
	 * type is not equivalent to void. This is an important sanity check.
	 *
	 * @param d
	 */
	private void checkNonEmpty(Decl.Variable d, LifetimeRelation lifetimes) {
		if (relaxedSubtypeOperator.isVoid(d.getType(), lifetimes)) {
			syntaxError(d.getType(), EMPTY_TYPE);
		}
	}

	/**
	 * From an arbitrary type, extract the array type it represents which is either
	 * readable or writeable depending on the context.
	 */
	public SemanticType.Array extractArrayType(SemanticType type, Environment environment,
			ReadWriteTypeExtractor.Combinator<SemanticType.Array> combinator, SyntacticItem item) {
		//
		if (type != null) {
			SemanticType.Array sourceArrayT = rwTypeExtractor.apply(type, environment, combinator);
			//
			if (sourceArrayT == null) {
				syntaxError(item, EXPECTED_ARRAY);
			} else {
				return sourceArrayT;
			}
		}
		return null;
	}

	/**
	 * Extract the element type from an array. The array type can be null if some
	 * earlier part of type checking generated an error message and we are just
	 * continuing after that.
	 *
	 * @param type
	 * @param item
	 * @return
	 */
	public SemanticType extractElementType(SemanticType.Array type, SyntacticItem item) {
		if (type == null) {
			return null;
		} else {
			return type.getElement();
		}
	}

	/**
	 * From an arbitrary type, extract the record type it represents which is either
	 * readable or writeable depending on the context.
	 */
	public SemanticType.Record extractRecordType(SemanticType type, Environment environment,
			ReadWriteTypeExtractor.Combinator<SemanticType.Record> combinator, SyntacticItem item) {
		//
		if (type != null) {
			SemanticType.Record recordT = rwTypeExtractor.apply(type, environment, combinator);
			//
			if (recordT == null) {
				syntaxError(item, EXPECTED_RECORD);
			} else {
				return recordT;
			}
		}
		return null;
	}

	/**
	 * From a given record type, extract type for a given field.
	 */
	public SemanticType extractFieldType(SemanticType.Record type, Identifier field) {
		if (type == null) {
			return null;
		} else {
			SemanticType fieldType = type.getField(field);
			if (fieldType == null) {
				// Indicates an invalid field selection
				syntaxError(field, INVALID_FIELD);
			}
			return fieldType;
		}
	}

	private Type extractConcreteFieldType(SemanticType.Record type, Identifier field, Environment environment) {
		SemanticType ft = extractFieldType(type, field);
		if (ft != null) {
			// Convert to concrete type
			return concreteTypeExtractor.apply(ft, environment);
		} else {
			return null;
		}
	}

	/**
	 * From an arbitrary type, extract the reference type it represents which is
	 * either readable or writeable depending on the context.
	 */
	public SemanticType.Reference extractReferenceType(SemanticType type, Environment environment,
			ReadWriteTypeExtractor.Combinator<SemanticType.Reference> combinator, SyntacticItem item) {
		//
		if (type != null) {
			SemanticType.Reference refT = rwTypeExtractor.apply(type, environment, combinator);
			//
			if (refT == null) {
				syntaxError(item, EXPECTED_REFERENCE);
			} else {
				return refT;
			}
		}
		//
		return null;
	}

	/**
	 * Extract the element type from a reference. The array type can be null if some
	 * earlier part of type checking generated an error message and we are just
	 * continuing after that.
	 *
	 * @param type
	 * @param item
	 * @return
	 */
	public SemanticType extractElementType(SemanticType.Reference type, SyntacticItem item) {
		if (type == null) {
			return null;
		} else {
			return type.getElement();
		}
	}

	/**
	 * From an arbitrary type, extract the lambda type it represents which is either
	 * readable or writeable depending on the context.
	 */
	public Type.Callable extractLambdaType(SemanticType type, Environment environment,
			ReadWriteTypeExtractor.Combinator<Type.Callable> combinator, SyntacticItem item) {
		//
		if (type != null) {
			Type.Callable refT = rwTypeExtractor.apply(type, environment, combinator);
			//
			if (refT == null) {
				syntaxError(item, EXPECTED_LAMBDA);
			} else {
				return refT;
			}
		}
		return null;
	}

	private void syntaxError(SyntacticItem e, int code, SyntacticItem... context) {
		status = false;
		ErrorMessages.syntaxError(e, code, context);
	}

	private void syntaxError(SyntacticItem e, int code, List<? extends SyntacticItem> context) {
		status = false;
		ErrorMessages.syntaxError(e, code, context.toArray(new SyntacticItem[context.size()]));
	}

	private <T> T internalFailure(String msg, SyntacticItem e) {
		CompilationUnit cu = (CompilationUnit) e.getHeap();
		throw new SyntacticException(msg, cu.getEntry(), e);
	}

	private <T> T internalFailure(String msg, SyntacticItem e, Throwable ex) {
		CompilationUnit cu = (CompilationUnit) e.getHeap();
		throw new SyntacticException(msg, cu.getEntry(), e, ex);
	}

	// ==========================================================================
	// Enclosing Scope
	// ==========================================================================

	/**
	 * An enclosing scope captures the nested of declarations, blocks and other
	 * statements (e.g. loops). It is used to store information associated with
	 * these things such they can be accessed further down the chain. It can also be
	 * used to propagate information up the chain (for example, the environments
	 * arising from a break or continue statement).
	 *
	 * @author David J. Pearce
	 *
	 */
	private abstract static class EnclosingScope {
		protected final EnclosingScope parent;

		public EnclosingScope(EnclosingScope parent) {
			this.parent = parent;
		}

		/**
		 * Get the innermost enclosing block of a given kind. For example, when
		 * processing a return statement we may wish to get the enclosing function or
		 * method declaration such that we can type check the return types.
		 *
		 * @param kind
		 */
		public <T> T getEnclosingScope(Class<T> kind) {
			if (kind.isInstance(this)) {
				return (T) this;
			} else if (parent != null) {
				return parent.getEnclosingScope(kind);
			} else {
				// FIXME: better error propagation?
				return null;
			}
		}
	}

	private interface LifetimeDeclaration {
		/**
		 * Get the list of all lifetimes declared by this or an enclosing scope. That is
		 * the complete set of lifetimes available at this point.
		 *
		 * @return
		 */
		public String[] getDeclaredLifetimes();
	}

	/**
	 * Represents the enclosing scope for a function or method declaration.
	 *
	 * @author David J. Pearce
	 *
	 */
	private static class FunctionOrMethodScope extends EnclosingScope implements LifetimeDeclaration {
		private final Decl.FunctionOrMethod declaration;

		public FunctionOrMethodScope(Decl.FunctionOrMethod declaration) {
			super(null);
			this.declaration = declaration;
		}

		public Decl.FunctionOrMethod getDeclaration() {
			return declaration;
		}

		@Override
		public String[] getDeclaredLifetimes() {
			if (declaration instanceof Decl.Method) {
				Decl.Method meth = (Decl.Method) declaration;
				Identifier[] lifetimes = meth.getLifetimes();
				String[] arr = new String[lifetimes.length + 1];
				for (int i = 0; i != lifetimes.length; ++i) {
					arr[i] = lifetimes[i].get();
				}
				arr[arr.length - 1] = "this";
				return arr;
			} else {
				return new String[] { "this" };
			}
		}
	}

	private static class NamedBlockScope extends EnclosingScope implements LifetimeDeclaration {
		private final Stmt.NamedBlock stmt;

		public NamedBlockScope(EnclosingScope parent, Stmt.NamedBlock stmt) {
			super(parent);
			this.stmt = stmt;
		}

		@Override
		public String[] getDeclaredLifetimes() {
			LifetimeDeclaration enclosing = parent.getEnclosingScope(LifetimeDeclaration.class);
			String[] declared = enclosing.getDeclaredLifetimes();
			declared = Arrays.copyOf(declared, declared.length + 1);
			declared[declared.length - 1] = stmt.getName().get();
			return declared;
		}
	}
}
