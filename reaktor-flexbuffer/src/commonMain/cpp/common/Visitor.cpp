#include <common/Visitor.h>

namespace FlatInvoker {
    unique_ptr<Geometry> Geometry::make(TypeTag tag) {
        switch (tag) {
            case POINT:
                return std::make_unique<Point>();
            case CIRCLE:
                return std::make_unique<Circle>();
            case LINE:
                return std::make_unique<Line>();
            case INTERSECTION:
                return std::make_unique<Intersection>();
        }
        throw Exception("Unknown geometry tag");
    }

    Point::Point(double x, double y) : x(x), y(y) {}

    void Point::accept(GeometryVisitor& visitor) {
        visitor.visit(*this);
    }

    Geometry::TypeTag Point::tag() const {
        return POINT;
    }

    Circle::Circle(Point centre, double radius) : centre(std::move(centre)), radius(radius) {}

    void Circle::accept(GeometryVisitor& visitor) {
        visitor.visit(*this);
    }

    Geometry::TypeTag Circle::tag() const {
        return CIRCLE;
    }

    Line::Line(Point start, Point end) : start(std::move(start)), end(std::move(end)) {}

    void Line::accept(GeometryVisitor& visitor) {
        visitor.visit(*this);
    }

    Geometry::TypeTag Line::tag() const {
        return LINE;
    }

    Intersection::Intersection(unique_ptr<Geometry> first, unique_ptr<Geometry> second)
        : first(std::move(first)), second(std::move(second)) {}

    void Intersection::accept(GeometryVisitor& visitor) {
        visitor.visit(*this);
    }

    Geometry::TypeTag Intersection::tag() const {
        return INTERSECTION;
    }

    void StringSerializerVisitor::visit(double& value) {
        stream_ << value << ' ';
    }

    void StringSerializerVisitor::visit(Point& point) {
        visit(point.x);
        visit(point.y);
    }

    void StringSerializerVisitor::visit(Circle& circle) {
        Point centre = circle.centre;
        visit(centre);
        double radius = circle.radius;
        visit(radius);
    }

    void StringSerializerVisitor::visit(Line& line) {
        Point start = line.start;
        Point end = line.end;
        visit(start);
        visit(end);
    }

    void StringSerializerVisitor::visit(Intersection& intersection) {
        auto writeChild = [this](const unique_ptr<Geometry>& geometry) {
            if (geometry == nullptr) {
                stream_ << 0 << ' ';
                return;
            }
            stream_ << 1 << ' ' << static_cast<int>(geometry->tag()) << ' ';
            geometry->accept(*this);
        };

        writeChild(intersection.first);
        writeChild(intersection.second);
    }

    string StringSerializerVisitor::str() const {
        return stream_.str();
    }

    StringDeserializerVisitor::StringDeserializerVisitor(string data) {
        stream_.str(std::move(data));
    }

    void StringDeserializerVisitor::visit(double& value) {
        stream_ >> value;
    }

    void StringDeserializerVisitor::visit(Point& point) {
        visit(point.x);
        visit(point.y);
    }

    void StringDeserializerVisitor::visit(Circle& circle) {
        visit(circle.centre);
        visit(circle.radius);
    }

    void StringDeserializerVisitor::visit(Line& line) {
        visit(line.start);
        visit(line.end);
    }

    void StringDeserializerVisitor::visit(Intersection& intersection) {
        intersection.first = readGeometry();
        intersection.second = readGeometry();
    }

    unique_ptr<Geometry> StringDeserializerVisitor::readGeometry() {
        int present = 0;
        stream_ >> present;
        if (present == 0) {
            return nullptr;
        }

        int rawTag = 0;
        stream_ >> rawTag;
        auto geometry = Geometry::make(static_cast<Geometry::TypeTag>(rawTag));
        geometry->accept(*this);
        return geometry;
    }
}
