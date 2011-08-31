package wyts.lang;

import static wyts.lang.Automata.State;

/**
 * <p>
 * The default interpretation provides a useful starting point for many
 * interpretations. It follows a uniform <i>covariant</i> protocol, as follows:
 * </p>
 * <ul>
 * <li>A state <code>s1</code> subsumes another state <code>s2</code> if they
 * have the same (sequential) kind and every child of <code>s1</code> at
 * position <code>i</code> subsumes the child of <code>s2</code> at that
 * position.</li>
 * <li>A state <code>s1</code> subsumes another state <code>s2</code> if they
 * have the same (non-sequential) kind and every child of <code>s2</code> is
 * subsumed by a child of <code>s1</code>.
 * </ul>
 * 
 * 
 * @author djp
 * 
 */
public class DefaultInterpretation implements Interpretation {
	
	public boolean isSubSet(int fromIndex, Automata from, int toIndex,
			Automata to, Relation relation) {
		State s1 = from.states[fromIndex];
		State s2 = to.states[toIndex];
		
		if(s1.kind == s2.kind) {
			int kind = s1.kind;
			
			if((kind & Automata.NONSEQUENTIAL) == 0) {
				// sequential case
				int[] s1children = s1.children;
				int[] s2children = s2.children;
				
				if(s1children.length != s2children.length) {
					return false;
				}
				
				int length = s1children.length;
				
				for (int k = 0; k != length; ++k) {
					int s1child = s1children[k];
					int s2child = s2children[k];
					if (!relation.isSubSet(s1child, s2child)) {
						return false;
					}
				}
				
				return true;
			} else {
				// non-sequential (i.e. more expensive) case
				int[] s1children = s1.children;
				int[] s2children = s2.children;				
				int s1length = s1children.length;
				int s2length = s2children.length;
				
				// Check every node in s2 is subsumed by a node in s1
				for(int k=0;k!=s2length;++k) {
					int s2child = s2children[k];
					boolean matched = false;
					for (int l = 0; l != s1length; ++l) {
						int s1child = s1children[l];
						if (relation.isSubSet(s1child, s2child)) {
							matched = true;
							break;
						}
					}
					if(!matched) {
						return false;
					}
				}				
				return true;
			}
		}
		
		return false;
	}	
}
