#pragma once

#include <common/CppBase.h>
#include <sstream>

namespace FlatInvoker {
    class Point;
    class Circle;
    class Line;
    class Intersection;

    class GeometryVisitor {
    public:
        virtual ~GeometryVisitor() = default;
        virtual void visit(double& value) = 0;
        virtual void visit(Point& point) = 0;
        virtual void visit(Circle& circle) = 0;
        virtual void visit(Line& line) = 0;
        virtual void visit(Intersection& intersection) = 0;
    };

    class Geometry {
    public:
        enum TypeTag {
            POINT = 100,
            CIRCLE,
            LINE,
            INTERSECTION,
        };

        virtual ~Geometry() = default;
        virtual void accept(GeometryVisitor& visitor) = 0;
        virtual TypeTag tag() const = 0;

        static unique_ptr<Geometry> make(TypeTag tag);
    };

    class Point : public Geometry {
    public:
        Point() = default;
        Point(double x, double y);

        void accept(GeometryVisitor& visitor) override;
        TypeTag tag() const override;

        double x = 0.0;
        double y = 0.0;
    };

    class Circle : public Geometry {
    public:
        Circle() = default;
        Circle(Point centre, double radius);

        void accept(GeometryVisitor& visitor) override;
        TypeTag tag() const override;

        Point centre;
        double radius = 0.0;
    };

    class Line : public Geometry {
    public:
        Line() = default;
        Line(Point start, Point end);

        void accept(GeometryVisitor& visitor) override;
        TypeTag tag() const override;

        Point start;
        Point end;
    };

    class Intersection : public Geometry {
    public:
        Intersection() = default;
        Intersection(unique_ptr<Geometry> first, unique_ptr<Geometry> second);

        void accept(GeometryVisitor& visitor) override;
        TypeTag tag() const override;

        unique_ptr<Geometry> first;
        unique_ptr<Geometry> second;
    };

    class StringSerializerVisitor : public GeometryVisitor {
    public:
        void visit(double& value) override;
        void visit(Point& point) override;
        void visit(Circle& circle) override;
        void visit(Line& line) override;
        void visit(Intersection& intersection) override;

        string str() const;

    private:
        std::stringstream stream_;
    };

    class StringDeserializerVisitor : public GeometryVisitor {
    public:
        explicit StringDeserializerVisitor(string data);

        void visit(double& value) override;
        void visit(Point& point) override;
        void visit(Circle& circle) override;
        void visit(Line& line) override;
        void visit(Intersection& intersection) override;

    private:
        unique_ptr<Geometry> readGeometry();

        std::stringstream stream_;
    };
}
