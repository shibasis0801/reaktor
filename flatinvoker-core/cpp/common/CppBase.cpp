#include <common/CppBase.h>

namespace FlatInvoker {
    Exception::Exception(std::string message): message(std::move(message)) {}
    const char *Exception::what() const noexcept {
        return message.c_str();
    }

    std::unique_ptr<flexbuffers::Builder> builder = std::make_unique<flexbuffers::Builder>(1024);
    FlexPointer FlexStore::Create() {
        builder->Clear();
        auto pointer = reinterpret_cast<FlexPointer>(builder.get());
        return pointer;
    }

    void FlexStore::Destroy(FlexPointer pointer) {
        FlexStore::Get(pointer)->Clear();
    }

    void FlexStore::Finish(FlexPointer pointer) {
        Get(pointer)->Finish();
    }

    flexbuffers::Builder *FlexStore::Get(FlexPointer pointer) {
        return reinterpret_cast<flexbuffers::Builder *>(pointer);
    }
} // namespace FlatInvoker


#define repeat(i, n) for (size_t i = 0; (i) < (n); ++(i))
using namespace std;

const char WALL = '+';
const char OPEN = '.';

typedef pair<int, int> ii;

inline bool equals(const ii &pair, int i, int j) {
    return pair.first == i && pair.second == j;
}


enum Boundary { IN, AT, OUT };

class Solution {
public:
    int findShortestPath(
            const vector<vector<char>>& maze,
            matrix<bool> &visited,
            const function<Boundary(ii)> &boundary,
            ii current
    ) {
        auto [i, j] = current;
        visited[i][j] = true;

        auto status = boundary(current);

        if (status == AT) {
            return (maze[i][j] == OPEN) ? 1 : -1;
        } else if (status == OUT) {
            return 0;
        }

        int cost = 999999999;

        auto left = make_pair(i, j - 1);
        if (boundary(left) != OUT && !visited(left))
            cost = min(cost, findShortestPath(maze, visited, boundary, left));

        auto right = make_pair(i, j + 1);
        if (boundary(right) != OUT && !visited(right))
            cost = min(cost, findShortestPath(maze, visited, boundary, right));

        auto top = make_pair(i - 1, j);
        if (boundary(top) != OUT && !visited(top))
            cost = min(cost, findShortestPath(maze, visited, boundary, right));

        auto bottom = make_pair(i + 1, j);
        if (boundary(bottom) != OUT && !visited(bottom))
            cost = min(cost, findShortestPath(maze, visited, boundary, right));

        return 1 + cost;
    }

    int nearestExit(vector<vector<char>>& maze, vector<int>& entrance) {
        pair<int, int> start { entrance[0], entrance[1] };

        int rows = maze.size();
        int cols = maze[0].size();

        matrix<bool> visited(rows, cols, false);

        const auto boundary = [&] (ii rc) -> Boundary {
            auto [i, j] = rc;
            if (i == rows - 1 || j == cols - 1) return AT;
            else if (i < rows && j < cols) return IN;
            else return OUT;
        };

        return findShortestPath(maze, visited, boundary, start);
    }
};