define expr as [int]|int
define tup as (expr lhs, int p)

void f(tup t):
    if t.lhs ~= [int] && |t.lhs| > 0 && t.lhs[0] == 0:
        print "MATCH" + str(t.lhs)
    else:
        print "NO MATCH"

void System::main([string] args):
    f((lhs:[0],p:0))
    f((lhs:[1],p:0))
    f((lhs:[],p:0)) 
