#include <common/CppBase.h>


class Pet;
class Cat;
class Dog;

class PetVisitor {
public:
    virtual void visit(Cat* c, Pet* p) = 0;
    virtual void visit(Dog* d, Pet* p) = 0;
};



class Pet {
public:
    virtual ~Pet() {}
    virtual void accept(PetVisitor& visitor, Pet* p = nullptr) = 0;

    Pet(string_view color): color_(color) {}
    const string& color() const { return color_; }
    void add_child(Pet *baby) {
        children.push_back(baby);
    }
    const vector<Pet*>& get_children() const {
        return children;
    }

private:
    const string color_;
    vector<Pet*> children;
};


class Cat: public Pet {
public:
    Cat(string_view color): Pet(color) {}

    void accept(PetVisitor &visitor, Pet* p = nullptr) override {
        visitor.visit(this, p);
    }

};

class Dog: public Pet {
public:
    Dog(string_view color): Pet(color) {}

    void accept(PetVisitor &visitor, Pet *p = nullptr) override {
        visitor.visit(this, p);
    }
};


#include <iostream>
class FeedingVisitor: public PetVisitor {
public:
    void visit(Cat *c, Pet *p = nullptr) override {
        std::cout << "Feeding cat: " << c->color() << std::endl;
    }

    void visit(Dog *d, Pet *p = nullptr) override {
        std::cout << "Feeding dog: " << d->color() << std::endl;
    }
};

class PlayingVisitor: public PetVisitor {
public:
    void visit(Cat *c, Pet *p = nullptr) override {
        std::cout << "Playing cat: " << c->color() << std::endl;
    }

    void visit(Dog *d, Pet *p = nullptr) override {
        std::cout << "Playing dog: " << d->color() << std::endl;
    }
};

class BabyCreationVisitor: public PetVisitor {
public:
    void visit(Cat *c, Pet *p) override {
        assert(dynamic_cast<Cat*>(p));
        c->add_child(p);
    }

    void visit(Dog *d, Pet *p) override {
        assert(dynamic_cast<Dog*>(p));
        d->add_child(p);
    }
};

class CensusVisitor: public PetVisitor {
public:
    void visit(Cat *c, Pet *p = nullptr) override {
        std::cout << c-> color();
        for (auto *baby: c->get_children()) {
            baby->accept(*this);
        }
    }

    void visit(Dog *d, Pet *p = nullptr) override {
        std::cout << d-> color();
        for (auto *baby: d->get_children()) {
            baby->accept(*this);
        }
    }
};

void PetTester() {
    auto pet = unique_ptr<Pet>(new Cat("orange"));
    FeedingVisitor fv;

    pet->accept(fv);
}


































class Visitor;

class Geometry {
public:
    virtual ~Geometry() {}
    virtual void accept(Visitor &v) = 0;
    enum type_tag { POINT = 100, CIRCLE, LINE, INTERSECTION };
    virtual type_tag tag() const = 0;

    static Geometry* make_geometry(Geometry::type_tag &tag);

};

class Point: public Geometry {
public:
    Point() = default;
    Point(double x, double y): x(x), y(y) {}

    void accept(Visitor &v) override;

    type_tag tag() const override { return POINT; };

private:
    double x, y;
};

class Circle: public Geometry {
public:
    Circle() = default;
    Circle(Point centre, double radius): centre(centre), radius(radius) {}

    void accept(Visitor &v) override;
    type_tag tag() const override { return CIRCLE; };
private:
    Point centre;
    double radius;
};

class Line: public Geometry {
public:
    Line() = default;
    Line(Point start, Point end): start(start), end(end) {}

    void accept(Visitor &v) override;
    type_tag tag() const override { return LINE; };
private:
    Point start;
    Point end;
};

class Intersection: public Geometry {
public:
    Intersection() = default;
    Intersection(Geometry *g1, Geometry *g2): g1(g1), g2(g2) {}

    type_tag tag() const override {
        return INTERSECTION;
    }
    void accept(Visitor &v) override;

private:
    std::unique_ptr<Geometry> g1;
    std::unique_ptr<Geometry> g2;
};

class Visitor {
public:
    virtual void visit(double& d) = 0;
    virtual void visit(Point& p) = 0;
    virtual void visit(Circle& c) = 0;
    virtual void visit(Line& l) = 0;
    virtual void visit(Geometry::type_tag& tag) = 0;

    static Geometry* make_geometry(Geometry::type_tag tag) {
        switch (tag) {
            case Geometry::POINT: return new Point();
            case Geometry::LINE: return new Line();
            case Geometry::CIRCLE: return new Circle();
            case Geometry::INTERSECTION: return new Intersection();
        }
    }
};


void Point::accept(Visitor &v) {
    v.visit(x);
    v.visit(y);
}

void Circle::accept(Visitor &v) {
    v.visit(centre);
    v.visit(radius);
}

void Line::accept(Visitor &v) {
    v.visit(start);
    v.visit(end);
}

void Intersection::accept(Visitor &v) {
    Geometry::type_tag tag;

    if (g1) {
        tag = g1->tag();
        v.visit(tag);
        g1->accept(v);
    }

    if (g2) {
        tag = g2->tag();
        v.visit(tag);
        g2->accept(v);
    }
}

class StringSerializerVisitor: public Visitor {
public:
    void visit(double& d) override {
        S << d << " ";
    }

    void visit(Point& p) override {
        p.accept(*this);
    }

    void visit(Circle& c) override {
        c.accept(*this);
    }

    void visit(Line& l) override {
        l.accept(*this);
    }

    void visit(Geometry::type_tag &tag) override {
        S << size_t(tag) << " ";
    }

    string str() {
        return S.str();
    }

private:
    std::stringstream S;
};


class StringDeserializationVisitor: public Visitor {
public:
    StringDeserializationVisitor(string s) {
        S.str(s);
    }

    void visit(double& d) override {
        S >> d;
    }

    void visit(Point& p) override {
        p.accept(*this);
    }

    void visit(Circle& c) override {
        c.accept(*this);
    }

    void visit(Line& l) override {
        l.accept(*this);
    }

    void visit(Geometry::type_tag &tag) override {
        size_t value;
        S >> value;
        tag = Geometry::type_tag(value);
    }

    string str() {
        return S.str();
    }

private:
    std::stringstream S;
};






































