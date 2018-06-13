type pos is (int x) where x >= 0
type neg is (int x) where x < 0

type R1 is {pos|neg f}

function f({pos f} x) -> (R1 r)
ensures r == x:
    return x

public export method test():
    assert f({f:0}) == {f:0}