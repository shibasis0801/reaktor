#include <common/CppBase.h>


class Cat;
class Dog;

class PetVisitor {
public:
    virtual void visit(Cat* c) = 0;
    virtual void visit(Dog* d) = 0;
};



class Pet {
public:
    virtual ~Pet() {}
    virtual void accept(PetVisitor& visitor) = 0;

    Pet(string_view color): color_(color) {}
    const string& color() const { return color_; }

private:
    const string color_;
};


class Cat: public Pet {
public:
    Cat(string_view color): Pet(color) {}

    void accept(PetVisitor &visitor) override {
        visitor.visit(this);
    }

};

class Dog: public Pet {
public:
    Dog(string_view color): Pet(color) {}

    void accept(PetVisitor &visitor) override {
        visitor.visit(this);
    }
};


#include <iostream>
class FeedingVisitor: public PetVisitor {
public:
    void visit(Cat *c) override {
        std::cout << "Feeding cat: " << c->color() << std::endl;
    }

    void visit(Dog *d) override {
        std::cout << "Feeding dog: " << d->color() << std::endl;
    }
};

class PlayingVisitor: public PetVisitor {
public:
    void visit(Cat *c) override {
        std::cout << "Playing cat: " << c->color() << std::endl;
    }

    void visit(Dog *d) override {
        std::cout << "Playing dog: " << d->color() << std::endl;
    }
};

void tester() {
    auto pet = unique_ptr<Pet>(new Cat("orange"));
    FeedingVisitor fv;

    pet->accept(fv);

}