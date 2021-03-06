// test lifetime inference with lifetime overloading and different types

method m<&a,&b>((&a:int)|(&b:bool) x, &a:int y, bool z) -> &b:int:
    return b:new 1

method m<&a>(&a:bool x, &a:int y, bool z) -> &a:int:
    return a:new 2

method m<&a>(&*:int x, &a:int y, int|bool z) -> &a:int:
    return a:new 3

public export method test():
    // variant1: <this, *> ((&this:int)|(&*:bool) x, &this:int, bool z)
    // variant2: no
    // variant3: <this> (&this:int x, &this:int y, int|bool z)
    m(new 1, this:new 2, true)
