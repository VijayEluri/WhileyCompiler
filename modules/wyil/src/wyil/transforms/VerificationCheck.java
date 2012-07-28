// Copyright (c) 2012, David J. Pearce (djp@ecs.vuw.ac.nz)
// All rights reserved.
//
// Redistribution and use in source and binary forms, with or without
// modification, are permitted provided that the following conditions are met:
//    * Redistributions of source code must retain the above copyright
//      notice, this list of conditions and the following disclaimer.
//    * Redistributions in binary form must reproduce the above copyright
//      notice, this list of conditions and the following disclaimer in the
//      documentation and/or other materials provided with the distribution.
//    * Neither the name of the <organization> nor the
//      names of its contributors may be used to endorse or promote products
//      derived from this software without specific prior written permission.
//
// THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
// ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
// WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
// DISCLAIMED. IN NO EVENT SHALL DAVID J. PEARCE BE LIABLE FOR ANY
// DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
// (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
// LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
// ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
// (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
// SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

package wyil.transforms;

import java.util.*;

import wybs.lang.Builder;
import wybs.lang.Path;
import wybs.lang.SyntacticElement;
import wybs.lang.SyntaxError;
import wyil.lang.*;
import wyil.lang.Code;
import wyil.util.ErrorMessages;
import wyil.util.BigRational;
import wyil.util.Pair;
import static wybs.lang.SyntaxError.*;
import static wyil.util.ErrorMessages.errorMessage;
import wyil.Transform;
import wyone.core.*;
import wyone.theory.list.WLengthOf;
import wyone.theory.list.WListAccess;
import wyone.theory.list.WListConstructor;
import wyone.theory.list.WListType;
import wyone.theory.list.WListVal;
import wyone.theory.logic.*;
import wyone.theory.numeric.*;
import wyone.theory.quantifier.WBoundedForall;
import wyone.theory.set.WSetConstructor;
import wyone.theory.set.WSetType;
import wyone.theory.set.WSetVal;
import wyone.theory.set.WSets;
import wyone.theory.tuple.WTupleAccess;
import wyone.theory.tuple.WTupleConstructor;
import wyone.theory.tuple.WTupleType;
import wyone.theory.tuple.WTupleVal;
import wyone.theory.type.WAnyType;
import wyone.theory.type.WTypes;
import wyone.theory.type.WVoidType;

/**
 * Responsible for compile-time checking of constraints. This involves
 * converting WYIL into the appropriate form for the automated theorem prover
 * (wyone).  
 * 
 * @author David J. Pearce
 * 
 */
public class VerificationCheck implements Transform {	
	/**
	 * limit on number of steps theorem prover is allowed to take.
	 */
	private int timeout = getTimeout();	
	
	/**
	 * Determines whether verification is enabled or not.
	 */
	private boolean enabled = getEnable();
	
	private Builder builder;
	
	private String filename;	
	
	public VerificationCheck(Builder builder) {
		this.builder = builder;
	}
	
	public static String describeEnable() {
		return "Enable/disable compile-time verification";
	}
	
	public static boolean getEnable() {
		return false; // default value
	}
	
	public void setEnable(boolean flag) {
		this.enabled = flag;
	}
	
	public static int getTimeout() {
		return 250;
	}
	
	public void setTimeout(int timeout) {
		this.timeout = timeout;
	}

	public void apply(WyilFile module) {
		if(enabled) {
			this.filename = module.filename();
			for(WyilFile.TypeDeclaration type : module.types()) {
				transform(type);
			}		
			for(WyilFile.MethodDeclaration method : module.methods()) {
				transform(method);
			}		
		}
	}
	
	protected void transform(WyilFile.TypeDeclaration def) {
		
	}
	
	protected void transform(WyilFile.MethodDeclaration method) {		
		for(WyilFile.Case c : method.cases()) {
			transform(c,method);
		}
	}
	
	protected void transform(WyilFile.Case methodCase, WyilFile.MethodDeclaration method) {
		WFormula constraint = WBool.TRUE;					
				
		// add type information available from parameters
		Type.FunctionOrMethod fmm = method.type();
		int paramStart = 0;
		for(int i=paramStart;i!=fmm.params().size();++i) {
			Type paramType = fmm.params().get(i); 
			WVariable pv = new WVariable(i + "$" + 0);
			constraint = WFormulas.and(constraint,
					WTypes.subtypeOf(pv, convert(paramType)));
		}
		
		Block precondition = methodCase.precondition();				
		
		if(precondition != null) {
			WFormula precon = transform(WBool.TRUE, true, precondition);
			constraint = WFormulas.and(constraint,precon);
		}
		
		transform(constraint,false,methodCase.body());
	}
	
	
	public static class Scope {
		public final int end;
		
		public Scope(int end) {
			this.end = end;
		}
	}
	
	private static class LoopScope<T extends Code.Loop> extends Scope {
		public final T loop;
		
		public LoopScope(T loop, int end) {
			super(end);
			this.loop = loop;
		}
	}
	
	private static class ForScope extends LoopScope<Code.ForAll> {
		public final WExpr src;
		public final WVariable var;

		public ForScope(Code.ForAll forall, int end, WExpr src, WVariable var) {
			super(forall, end);
			this.src = src;
			this.var = var;
		}
	}
	
	/**
	 * Represents a path through the control-flow graph which has not yet been
	 * explored.
	 * 
	 * @author djp
	 * 
	 */
	private static class Branch {
		public final int pc;
		public final WFormula constraint;
		public final int[] environment;
		public final ArrayList<Scope> scopes;

		public Branch(int pc, WFormula constraint, int[] environment,
				ArrayList<Scope> scopes) {
			this.pc = pc;
			this.constraint = constraint;
			this.environment = Arrays.copyOf(environment,environment.length);
			this.scopes = new ArrayList<Scope>(scopes);
		}
	}
	
	protected WFormula transform(WFormula constraint, boolean assumes, Block blk) {
		ArrayList<Branch> branches = new ArrayList<Branch>();
		ArrayList<Scope> scopes = new ArrayList<Scope>();
		int[] environment = new int[blk.numSlots()];

		// take initial branch
		constraint = transform(0, constraint, environment, scopes,
				branches, assumes, blk);

		// continue any resulting branches
		while (!branches.isEmpty()) {
			int last = branches.size() - 1;
			Branch branch = branches.get(last);
			branches.remove(last);
			constraint = WFormulas
					.or(constraint,
							transform(branch.pc, branch.constraint,
									branch.environment, 
									branch.scopes, branches, assumes, blk));
		}
		
		// The following is necessary to prevent any possible clashes between
		// temporary variables used in pre- and post-conditions which are then
		// merged into the running constraint.
		HashMap<WExpr,WExpr> binding = new HashMap<WExpr,WExpr>();
		for(int i=blk.numInputs();i<blk.numSlots();++i) {
			for(int j=0;j<=environment[i];++j) {
				binding.put(new WVariable(i + "$" + j), WVariable.freshVar());
			}
		}

		return constraint.substitute(binding);
	}
	
	protected WFormula transform(int pc, WFormula constraint, int[] environment,
			ArrayList<Scope> scopes,
			ArrayList<Branch> branches, boolean assumes, Block body) {
		
		// the following is necessary for branches generated from conditionals
		
		int bodySize = body.size();		
		for (int i = pc; i != bodySize; ++i) {	
			constraint = exitScope(constraint,environment,scopes,i);
			
			Block.Entry entry = body.get(i);			
			Code code = entry.code;
			
			if(code instanceof Code.Goto) {
				Code.Goto g = (Code.Goto) code;
				i = findLabel(i,g.target,body);					
			} else if(code instanceof Code.If) {
				Code.If ifgoto = (Code.If) code;
				WFormula test = buildTest(ifgoto.op, entry, ifgoto.leftOperand,
						ifgoto.rightOperand, environment);				
				int targetpc = findLabel(i,ifgoto.target,body)	;
				branches.add(new Branch(targetpc, WFormulas.and(constraint,
						test), environment, scopes));
				constraint = WFormulas.and(constraint,test.not());
			} else if(code instanceof Code.IfIs) {
				// TODO: implement me!
			} else if(code instanceof Code.ForAll) {
				Code.ForAll forall = (Code.ForAll) code; 
				int end = findLabel(i,forall.target,body);
				WExpr src = operand(forall.sourceOperand,environment);
				WVariable var = new WVariable(forall.indexOperand + "$"
						+ environment[forall.indexOperand]);
				constraint = WFormulas.and(constraint,
						WTypes.subtypeOf(var, convert(forall.type.element())));
				
				if (forall.type instanceof Type.EffectiveList) {
					// We have to treat lists differently from sets because of the
					// way wyone handles list quantification. It's kind of annoying,
					// but there's not much we can do.
					WVariable index = WVariable.freshVar();
					constraint = WFormulas.and(constraint,
							WExprs.equals(var, new WListAccess(src,index)),
							WNumerics.lessThanEq(WNumber.ZERO, index),
							WNumerics.lessThan(index, new WLengthOf(src)),
							WTypes.subtypeOf(index, WIntType.T_INT));
					scopes.add(new ForScope(forall,end,src,index));
				} else if (forall.type instanceof Type.EffectiveSet) {
					Type.EffectiveSet es = (Type.EffectiveSet) forall.type;
					constraint = WFormulas.and(constraint, WSets.elementOf(var, src));
					scopes.add(new ForScope(forall,end,src,var));
				} else if (forall.type instanceof Type.EffectiveMap) {
					// TODO
				}
				
				// FIXME: assume loop invariant?
			} else if(code instanceof Code.Loop) {
				Code.Loop loop = (Code.Loop) code; 
				int end = findLabel(i,loop.target,body);
				scopes.add(new LoopScope(loop,end));
				// FIXME: assume loop invariant?
				// FIXME: assume condition?
			} else if(code instanceof Code.Return) {
				// we don't need to do anything for a return!
				return constraint;
			} else {
				constraint = transform(entry, constraint, environment, assumes);
			}
		}
		
		return constraint;
	}
	
	private static WFormula exitScope(WFormula constraint,
			int[] environment, ArrayList<Scope> scopes, int pc) {
		
		while (!scopes.isEmpty() && top(scopes).end <= pc) {
			// yes, we're exiting a scope
			Scope scope = pop(scopes);
			
			if(scope instanceof LoopScope) {
				LoopScope lscope = (LoopScope) scope;

				// trash modified variables
				for (int register : lscope.loop.modifiedOperands) {
					environment[register] = environment[register] + 1;
				}
				if(lscope instanceof ForScope) {
					ForScope fscope = (ForScope) lscope;
					// existing for all scope so existentially quantify generated
					// formula
					WVariable var = fscope.var;
					// Split for the formula into those bits which need to be
					// quantified, and those which don't
					Pair<WFormula, WFormula> split = splitFormula(var.name(),
							constraint);
					
					if(pc == fscope.end) { 						
						constraint = WFormulas.and(
								split.second(),
								new WBoundedForall(true, var, fscope.src, split
										.first()));
					} else {
						constraint = WFormulas.and(
								split.second(),
								new WBoundedForall(false, var, fscope.src, split
										.first().not()));						
					}
				}
			}			
		}
		
		return constraint;
	}
	
	private static int findLabel(int i, String label, Block body) {
		for(;i!=body.size();++i) {
			Code code = body.get(i).code;
			if(code instanceof Code.Label) {
				Code.Label l = (Code.Label) code;
				if(l.label.equals(label)) {
					return i;
				}
			}
		}
		// technically, this is dead-code.
		return i;
	}
	
	/**
	 * Transform the given constraint according to the abstract semantics of the
	 * given (simple) instruction (entry). The environment maps slots to their
	 * current single assignment number. Likewise, the stack models the stack
	 * and accumulates expressions.
	 * 
	 * @param entry
	 *            --- contains bytecode in question to apply.
	 * @param constraint
	 *            --- strongest condition which holds before this bytecode.
	 * @param environment
	 *            --- map of variables to their current single assignment
	 *            number.
	 * @param stack
	 *            --- current stack of intermediate expressions.
	 * @param assume
	 *            --- if true, indicates assumption mode.
	 * @return
	 */
	protected WFormula transform(Block.Entry entry, WFormula constraint,
			int[] environment, boolean assume) {
		Code code = entry.code;		
		
		try {
		if(code instanceof Code.Assert) {
			constraint = transform((Code.Assert)code,entry,constraint,environment,assume);
		} else if(code instanceof Code.BinArithOp) {
			constraint = transform((Code.BinArithOp)code,entry,constraint,environment);
		} else if(code instanceof Code.Convert) {
			constraint = transform((Code.Convert)code,entry,constraint,environment);
		} else if(code instanceof Code.Const) {
			constraint = transform((Code.Const)code,entry,constraint,environment);
		} else if(code instanceof Code.Debug) {
			// skip
		} else if(code instanceof Code.FieldLoad) {
			constraint = transform((Code.FieldLoad)code,entry,constraint,environment);			
		} else if(code instanceof Code.IndirectInvoke) {
			constraint = transform((Code.IndirectInvoke)code,entry,constraint,environment);
		} else if(code instanceof Code.Invoke) {
			constraint = transform((Code.Invoke)code,entry,constraint,environment);
		} else if(code instanceof Code.Invert) {
			constraint = transform((Code.Invert)code,entry,constraint,environment);
		} else if(code instanceof Code.Label) {
			// skip			
		} else if(code instanceof Code.BinListOp) {
			constraint = transform((Code.BinListOp)code,entry,constraint,environment);
		} else if(code instanceof Code.LengthOf) {
			constraint = transform((Code.LengthOf)code,entry,constraint,environment);
		} else if(code instanceof Code.SubList) {
			constraint = transform((Code.SubList)code,entry,constraint,environment);
		} else if(code instanceof Code.IndexOf) {
			constraint = transform((Code.IndexOf)code,entry,constraint,environment);
		} else if(code instanceof Code.Move) {
			constraint = transform((Code.Move)code,entry,constraint,environment);
		} else if(code instanceof Code.Assign) {
			constraint = transform((Code.Assign)code,entry,constraint,environment);
		} else if(code instanceof Code.Update) {
			constraint = transform((Code.Update)code,entry,constraint,environment);
		} else if(code instanceof Code.NewMap) {
			constraint = transform((Code.NewMap)code,entry,constraint,environment);
		} else if(code instanceof Code.NewList) {
			constraint = transform((Code.NewList)code,entry,constraint,environment);
		} else if(code instanceof Code.NewRecord) {
			constraint = transform((Code.NewRecord)code,entry,constraint,environment);
		} else if(code instanceof Code.NewSet) {
			constraint = transform((Code.NewSet)code,entry,constraint,environment);
		} else if(code instanceof Code.NewTuple) {
			constraint = transform((Code.NewTuple)code,entry,constraint,environment);
		} else if(code instanceof Code.UnArithOp) {
			constraint = transform((Code.UnArithOp)code,entry,constraint,environment);
		} else if(code instanceof Code.Dereference) {
			constraint = transform((Code.Dereference)code,entry,constraint,environment);
		} else if(code instanceof Code.Nop) {
			// skip			
		} else if(code instanceof Code.BinSetOp) {
			constraint = transform((Code.BinSetOp)code,entry,constraint,environment);
		} else if(code instanceof Code.BinStringOp) {
			constraint = transform((Code.BinStringOp)code,entry,constraint,environment);
		} else if(code instanceof Code.SubString) {
			constraint = transform((Code.SubString)code,entry,constraint,environment);
		} else if(code instanceof Code.NewObject) {
			constraint = transform((Code.NewObject)code,entry,constraint,environment);
		} else if(code instanceof Code.Throw) {
			constraint = transform((Code.Throw)code,entry,constraint,environment);
		} else if(code instanceof Code.TupleLoad) {
			constraint = transform((Code.TupleLoad)code,entry,constraint,environment);
		} else {			
			internalFailure("unknown: " + code.getClass().getName(),filename,entry);
			return null;
		}
		} catch(InternalFailure e) {
			throw e;
		} catch(SyntaxError e) {
			throw e;
		} catch(Throwable e) {
			internalFailure(e.getMessage(),filename,entry,e);
		}
		return constraint;
	}
	
	protected WFormula transform(Code.Assert code, Block.Entry entry,
			WFormula constraint, int[] environment,
			boolean assume) {
		// At this point, what we do is invert the condition being asserted and
		// check that it is unsatisfiable.
		WFormula test = buildTest(code.op, entry, code.leftOperand, code.rightOperand, environment);
		
		if (assume) {
			// in assumption mode we don't assert the test; rather, we assume
			// it. 
		} else {
//			System.out.println("======================================");
//			System.out.println("CHECKING: " + test.not() + " && " + constraint);
//			System.out.println("======================================");
			// Pass constraint through the solver to check for unsatisfiability
			Proof tp = Solver.checkUnsatisfiable(timeout,
					WFormulas.and(test.not(), constraint),
					wyone.Main.heuristic, wyone.Main.theories);

			// If constraint was satisfiable, then we have an error.
			if (!(tp instanceof Proof.Unsat)) {
				syntaxError(code.msg, filename, entry);
			}
		}
		
		return WFormulas.and(test, constraint);
	}
	
	protected WFormula transform(Code.BinArithOp code, Block.Entry entry,
			WFormula constraint, int[] environment) {
		WExpr lhs = operand(code.leftOperand,environment);
		WExpr rhs = operand(code.rightOperand,environment);
		WExpr result;
		
		switch(code.kind) {
		case ADD:
			result = WNumerics.add(lhs, rhs);
			break;
		case SUB:
			result = WNumerics.subtract(lhs, rhs);
			break;
		case MUL:
			result = WNumerics.multiply(lhs, rhs);
			break;
		case DIV:
			result = WNumerics.divide(lhs, rhs);			
			break;	
		default:
			internalFailure("unknown binary operator",filename,entry);
			return null;
		}
				
		return update(code.target,result,environment,constraint);
	}

	protected WFormula transform(Code.Convert code, Block.Entry entry,
			WFormula constraint, int[] environment) {
		WVariable result = operand(code.operand, environment);
		return update(code.target, result, environment, constraint);
	}

	protected WFormula transform(Code.Const code, Block.Entry entry,
			WFormula constraint, int[] environment) {
		return update(code.target, convert(code.constant, entry), environment,
				constraint);
	}

	protected WFormula transform(Code.FieldLoad code, Block.Entry entry,
			WFormula constraint, int[] environment) {
		WVariable src = operand(code.operand, environment);
		WExpr result = new WTupleAccess(src, code.field);
		return update(code.target, result, environment, constraint);
	}

	protected WFormula transform(Code.IndirectInvoke code, Block.Entry entry,
			WFormula constraint, int[] environment) {
		// TODO: complete this transform
		return constraint;
	}

	protected WFormula transform(Code.Invoke code, Block.Entry entry,
			WFormula constraint, int[] environment)
			throws Exception {
		
		// first, take arguments off the stack
		Type.FunctionOrMethod ft = code.type;
		List<Type> ft_params = code.type.params();
		ArrayList<WExpr> args = new ArrayList<WExpr>();
		HashMap<WExpr,WExpr> binding = new HashMap<WExpr,WExpr>();
		int[] code_operands = code.operands;
		for(int i=0;i!=code_operands.length;++i) {
			WExpr arg = operand(code_operands[i],environment);
			args.add(arg);
			binding.put(new WVariable(i + "$0"), arg);
		}			
		
		// second, setup return value
		if (code.target != Code.NULL_REG) {
			WVariable rhs = new WVariable(code.name.toString(), args);

			constraint = WFormulas.and(constraint,
					WTypes.subtypeOf(rhs, convert(ft.ret())));

			// now deal with post-condition
			Block postcondition = findPostcondition(code.name, ft, entry);
			if (postcondition != null) {
				WFormula pc = transform(WBool.TRUE, true, postcondition);
				binding.put(new WVariable("0$0"), rhs);
				constraint = WFormulas.and(constraint, pc.substitute(binding));
			}

			return update(code.target, rhs, environment, constraint);
		}
		
		return constraint;
	}

	protected WFormula transform(Code.Invert code, Block.Entry entry,
			WFormula constraint, int[] environment) {
		// TODO: complete this transform
		return constraint;
	}

	protected WFormula transform(Code.BinListOp code, Block.Entry entry,
			WFormula constraint, int[] environment) {
		// TODO: complete this transform
		return constraint;
	}

	protected WFormula transform(Code.LengthOf code, Block.Entry entry,
			WFormula constraint, int[] environment) {
		WExpr src = operand(code.operand, environment);
		WExpr result = new WLengthOf(src);
		return update(code.target, result, environment, constraint);
	}

	protected WFormula transform(Code.SubList code, Block.Entry entry,
			WFormula constraint, int[] environment) {
		// TODO: complete this transform
		return constraint;
	}

	protected WFormula transform(Code.IndexOf code, Block.Entry entry,
			WFormula constraint, int[] environment) {
		WExpr src = operand(code.leftOperand, environment);
		WExpr idx = operand(code.rightOperand, environment);
		WExpr result = new WListAccess(src, idx);
		return update(code.target, result, environment, constraint);
	}

	protected WFormula transform(Code.Move code, Block.Entry entry,
			WFormula constraint, int[] environment) {
		return update(code.target, operand(code.operand, environment),
				environment, constraint);
	}
	
	protected WFormula transform(Code.Assign code, Block.Entry entry,
			WFormula constraint, int[] environment) {
		return update(code.target, operand(code.operand, environment),
				environment, constraint);
	}

	protected WFormula transform(Code.Update code, Block.Entry entry,
			WFormula constraint, int[] environment) {
		// TODO: complete this transform
		return constraint;
	}

	protected WFormula transform(Code.NewMap code, Block.Entry entry,
			WFormula constraint, int[] environment) {
		// TODO: complete this transform
		return constraint;
	}

	protected WFormula transform(Code.NewList code, Block.Entry entry,
			WFormula constraint, int[] environment) {
		ArrayList<WExpr> args = new ArrayList<WExpr>();
		int[] code_operands = code.operands;
		for (int i=0;i!=code_operands.length;++i) {			
			args.add(operand(code_operands[i],environment));			
		}
		
		WExpr result = new WListConstructor(args);
		return update(code.target,result,environment,constraint);
	}

	protected WFormula transform(Code.NewSet code, Block.Entry entry,
			WFormula constraint, int[] environment) {
		HashSet<WExpr> args = new HashSet<WExpr>();
		int[] code_operands = code.operands;
		for (int i = 0; i != code_operands.length; ++i) {
			args.add(operand(code_operands[i], environment));
		}

		WExpr result = new WSetConstructor(args);
		return update(code.target, result, environment, constraint);
	}

	protected WFormula transform(Code.NewRecord code, Block.Entry entry,
			WFormula constraint, int[] environment) {
		Type.Record type = code.type;
		ArrayList<String> fields = new ArrayList<String>(type.fields().keySet());
		ArrayList<WExpr> args = new ArrayList<WExpr>();
		int[] code_operands = code.operands;
		for (int i = 0; i != code_operands.length; ++i) {
			args.add(operand(code_operands[i], environment));
		}		
		WExpr result = new WTupleConstructor(fields, args);
		return update(code.target,result,environment,constraint);
	}

	protected WFormula transform(Code.NewTuple code, Block.Entry entry,
			WFormula constraint, int[] environment) {
		ArrayList<String> fields = new ArrayList<String>();
		ArrayList<WExpr> args = new ArrayList<WExpr>();
		int[] code_operands = code.operands;
		for (int i = 0; i != code_operands.length; ++i) {
			args.add(operand(code_operands[i], environment));
			fields.add(Integer.toString(i));
		}
		WExpr result = new WTupleConstructor(fields, args);
		return update(code.target, result, environment, constraint);
	}

	protected WFormula transform(Code.UnArithOp code, Block.Entry entry,
			WFormula constraint, int[] environment) {
		// TODO: update to support numerator and denominators
		WExpr expr = operand(code.operand, environment);
		WExpr result = WNumerics.negate(expr);
		return update(code.target, result, environment, constraint);
	}

	protected WFormula transform(Code.Dereference code, Block.Entry entry,
			WFormula constraint, int[] environment) {
		// TODO: complete this transform
		return constraint;
	}

	protected WFormula transform(Code.BinSetOp code, Block.Entry entry,
			WFormula constraint, int[] environment) {
		WVariable lhs = operand(code.leftOperand, environment);
		WVariable rhs = operand(code.rightOperand, environment);
		WVariable target = update(code.target, environment);

		WVariable tmp = WVariable.freshVar();
		WSetConstructor sc = new WSetConstructor(tmp);
		
		HashMap<WVariable, WExpr> vars = new HashMap();
		vars.put(tmp, target);
		
		switch(code.kind) {
		case UNION:	{
			
			WFormula allc = WFormulas.or(WSets.subsetEq(sc, lhs),
				WSets.subsetEq(sc, rhs));
			constraint = WFormulas.and(constraint, WSets.subsetEq(lhs, target), WSets
					.subsetEq(rhs, target), new WBoundedForall(true, vars, allc));
			break;
		}
		case DIFFERENCE: {
			WFormula left = new WBoundedForall(true, vars, WFormulas.and(WSets
					.subsetEq(sc, lhs), WSets.subsetEq(sc, rhs).not()));

			constraint = WFormulas
					.and(constraint, WSets.subsetEq(lhs, target), left);		
			break;
		}
		case INTERSECTION:
			WFormula left = new WBoundedForall(true, vars, WFormulas.and(WSets
					.subsetEq(sc, lhs), WSets.subsetEq(sc, rhs)));
			vars = new HashMap();
			vars.put(tmp, lhs);
			WFormula right = new WBoundedForall(true, vars, WFormulas.implies(WSets
					.subsetEq(sc, rhs), WSets.subsetEq(sc, target)));
			
			constraint = WFormulas
					.and(constraint, left, right, WSets.subsetEq(target, lhs), WSets.subsetEq(target, rhs));
			break;
			default:
				internalFailure("missing support for left/right set operations",filename,entry);
		}

		return constraint;
	}
	
	protected WFormula transform(Code.BinStringOp code, Block.Entry entry,
			WFormula constraint, int[] environment) {
		// TODO: complete this transform
		return constraint;
	}

	protected WFormula transform(Code.SubString code, Block.Entry entry,
			WFormula constraint, int[] environment) {
		// TODO: complete this transform
		return constraint;
	}

	protected WFormula transform(Code.NewObject code, Block.Entry entry,
			WFormula constraint, int[] environment) {
		// TODO: complete this transform
		return constraint;
	}

	protected WFormula transform(Code.Throw code, Block.Entry entry,
			WFormula constraint, int[] environment) {
		// TODO: complete this transform
		return constraint;
	}

	protected WFormula transform(Code.TupleLoad code, Block.Entry entry,
			WFormula constraint, int[] environment) {
		WExpr src = operand(code.operand, environment);
		WExpr result = new WTupleAccess(src, Integer.toString(code.index));
		return update(code.target, result, environment, constraint);
	}
	
	protected Block findPostcondition(NameID name, Type.FunctionOrMethod fun,
			SyntacticElement elem) throws Exception {
		Path.Entry<WyilFile> e = builder.namespace().get(name.module(),
				WyilFile.ContentType);
		if (e == null) {
			syntaxError(
					errorMessage(ErrorMessages.RESOLUTION_ERROR, name.module()
							.toString()), filename, elem);
		}
		WyilFile m = e.read();
		WyilFile.MethodDeclaration method = m.method(name.name(), fun);

		for (WyilFile.Case c : method.cases()) {
			// FIXME: this is a hack for now
			return c.postcondition();
		}
		return null;
	}
	
	// The following method splits a formula into two components: those bits
	// which use the given variable (left), and those which don't (right). This
	// is done to avoid quantifying more than is necessary when dealing with
	// loops.
	protected static Pair<WFormula, WFormula> splitFormula(String var, WFormula f) {
		if (f instanceof WConjunct) {
			WConjunct c = (WConjunct) f;
			WFormula ts = WBool.TRUE;
			WFormula fs = WBool.TRUE;
			for (WFormula st : c.subterms()) {
				Pair<WFormula, WFormula> r = splitFormula(var, st);
				ts = WFormulas.and(ts, r.first());
				fs = WFormulas.and(fs, r.second());
			}
			return new Pair<WFormula, WFormula>(ts, fs);
		} else {
			// not a conjunct, so check whether or not this uses var or not.
			Set<WVariable> uses = WExprs.match(WVariable.class, f);
			for (WVariable v : uses) {
				if (v.name().equals(var)) {
					return new Pair<WFormula, WFormula>(f, WBool.TRUE);
				}
			}
			// Ok, doesn't use variable
			return new Pair<WFormula, WFormula>(WBool.TRUE, f);
		}
	}
	/**
	 * Convert between a WYIL value and a WYONE value. Basically, this is really
	 * stupid and it would be good for them to be the same.
	 * 
	 * @param value
	 * @return
	 */
	private WValue convert(wyil.lang.Constant value, SyntacticElement elem) {
		if(value instanceof wyil.lang.Constant.Bool) {
			wyil.lang.Constant.Bool b = (wyil.lang.Constant.Bool) value;
			return b.value ? WBool.TRUE : WBool.FALSE;
		} else if(value instanceof wyil.lang.Constant.Byte) {
			wyil.lang.Constant.Byte v = (wyil.lang.Constant.Byte) value;
			return new WNumber(v.value);
		} else if(value instanceof wyil.lang.Constant.Char) {
			wyil.lang.Constant.Char v = (wyil.lang.Constant.Char) value;
			// Simple, but mostly good translation
			return new WNumber(v.value);
		} else if(value instanceof wyil.lang.Constant.Map) {
			return WBool.FALSE; // FIXME
		} else if(value instanceof wyil.lang.Constant.FunctionOrMethod) {
			return WBool.FALSE; // FIXME
		} else if(value instanceof wyil.lang.Constant.Integer) {
			wyil.lang.Constant.Integer v = (wyil.lang.Constant.Integer) value;
			return new WNumber(v.value);
		} else if(value instanceof wyil.lang.Constant.List) {
			Constant.List vl = (Constant.List) value;
			ArrayList<WValue> vals = new ArrayList<WValue>();
			for(Constant e : vl.values) {
				vals.add(convert(e,elem));
			}
			return new WListVal(vals);
		} else if(value instanceof wyil.lang.Constant.Null) {
			return WBool.FALSE; // FIXME
		} else if(value instanceof wyil.lang.Constant.Rational) {
			wyil.lang.Constant.Rational v = (wyil.lang.Constant.Rational) value;
			BigRational br = v.value;
			return new WNumber(br.numerator(),br.denominator());
		} else if(value instanceof wyil.lang.Constant.Record) {
			Constant.Record vt = (Constant.Record) value;
			ArrayList<String> fields = new ArrayList<String>(vt.values.keySet());
			ArrayList<WValue> values = new ArrayList<WValue>();
			Collections.sort(fields);
			for(String f : fields) {			
				values.add(convert(vt.values.get(f),elem));
			}
			return new WTupleVal(fields,values);
		} else if(value instanceof wyil.lang.Constant.Set) {
			Constant.Set vs = (Constant.Set) value;			
			HashSet<WValue> vals = new HashSet<WValue>();
			for(Constant e : vs.values) {
				vals.add(convert(e,elem));
			}
			return new WSetVal(vals);
		} else if(value instanceof wyil.lang.Constant.Strung) {
			return WBool.FALSE; // FIXME
		} else if(value instanceof wyil.lang.Constant.Tuple) {
			Constant.Tuple vt = (Constant.Tuple) value;
			ArrayList<String> fields = new ArrayList<String>();
			ArrayList<WValue> values = new ArrayList<WValue>();
			int i = 0;
			for(Constant e : vt.values) {			
				values.add(convert(e,elem));
				fields.add(""+i++);
			}
			return new WTupleVal(fields,values);
		} else {
			internalFailure("unknown value encountered (" + value + ")",filename,elem);
			return null;
		}
	}
	
	/**
	 * Convert a Wyil type into a Wyone type. Mostly, the conversion is
	 * straightforward and obvious.
	 * 
	 * @param type
	 * @return
	 */
	protected WType convert(Type type) {
		if(type == Type.T_VOID) {
			return WVoidType.T_VOID;
		} else if(type == Type.T_BOOL) {
			return WBoolType.T_BOOL;
		} else if(type == Type.T_INT) {
			return WIntType.T_INT;
		} else if(type == Type.T_REAL) {
			return WRealType.T_REAL;
		} else if(type instanceof Type.EffectiveList) {
			Type.EffectiveList tl = (Type.EffectiveList) type;
			return new WListType(convert(tl.element()));			
		} else if(type instanceof Type.EffectiveSet) {
			Type.EffectiveSet tl = (Type.EffectiveSet) type;
			return new WSetType(convert(tl.element()));			
		} else if(type instanceof Type.EffectiveRecord) {
			Type.EffectiveRecord tl = (Type.EffectiveRecord) type;
			ArrayList<String> keys = new ArrayList<String>(tl.fields().keySet());
			ArrayList<wyone.util.Pair<String,WType>> types = new ArrayList();
			Collections.sort(keys);
			for(String key : keys) {
				types.add(new wyone.util.Pair(key,convert(tl.fields().get(key))));
			}
			return new WTupleType(types);
		} else {
			return WAnyType.T_ANY;
		}
	}
	/**
	 * Generate a formula representing a condition from an Code.IfCode or
	 * Code.Assert bytecodes.
	 * 
	 * @param op
	 * @param stack
	 * @param elem
	 * @return
	 */
	private WFormula buildTest(Code.Comparator op, SyntacticElement elem, int leftOperand, int rightOperand, int[] environment) {
		WExpr lhs = operand(leftOperand,environment);
		WExpr rhs = operand(rightOperand,environment);
		
		switch(op) {
		case EQ:
			return WExprs.equals(lhs, rhs);
		case NEQ:
			return WExprs.notEquals(lhs, rhs);
		case GTEQ:
			return WNumerics.greaterThanEq(lhs, rhs);
		case GT:
			return WNumerics.greaterThan(lhs, rhs);
		case LTEQ:
			return WNumerics.lessThanEq(lhs, rhs);
		case LT:
			return WNumerics.lessThan(lhs, rhs);
		case SUBSET:
			return WSets.subset(lhs, rhs);
		case SUBSETEQ:
			return WSets.subsetEq(lhs, rhs);
		case ELEMOF:
			return WSets.elementOf(lhs, rhs);
		default:
			internalFailure("unknown comparator (" + op + ")",filename,elem);
			return null;
		}
	}
	
	private static WVariable operand(int register, int[] environment) {
		return new WVariable(register + "$" + environment[register]);
	}

	private static WVariable update(int target, int[] environment) {
		int nval = environment[target] + 1;
		environment[target] = nval;
		WVariable lhs = new WVariable(target + "$" + nval);
		return lhs;
	}
	
	private static WFormula update(int target, WExpr rhs, int[] environment,
			WFormula constraint) {
		int nval = environment[target] + 1;
		environment[target] = nval;
		WVariable lhs = new WVariable(target + "$" + nval);
		return WFormulas.and(constraint, WExprs.equals(lhs, rhs));
	}
	
	private static <T> T pop(ArrayList<T> stack) {
		int last = stack.size()-1;
		T c = stack.get(last);
		stack.remove(last);
		return c;
	}
	
	private static <T> T top(ArrayList<T> stack) {
		int last = stack.size()-1;
		T c = stack.get(last);
		return c;
	}
}