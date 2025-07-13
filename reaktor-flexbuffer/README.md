Shibasis, 27
data class Person(val name: String, val age: Int)
shibasis\027

type Person = { name: String, age: Int }
shibasis - 27

struct Person {
    std::string name;
    int age;
}
shibasis\0 - 27

shibasis\27\

json -> slow
 > slow
    > needs parse
 > O(logn)
 >
proto -> fast
 > faster
 > needs parse
 > O(1)
 >


class Person {
    String name;
    int age;
}


{
field person -> string
field age -> number
}

FlatBuffers 
independent memory format




Person(hello, 27)

[ h, e, l, l, o, SENTINEL, 2, 7 ] <- binary proto
{ 
    
} <- text json


{
    [ "hello", 27 ] 
    fields: {
            person: 0,
            age: 1
        }    
    }
}


1. Project Panama -> Increase FFI speed
2. Static Hermes Zero Cost FFI -> Increase FFI speed
3. Swift C++ Interop -> Increase speed
4. FlexBuffer -> Language independent transmission supported format