#pragma once
#include <common/CppBase.h>

template<class T>
struct matrix {
    vector<vector<T>> data;

    matrix(size_t rows, size_t cols): data(vector<vector<T>>(rows, vector<T>(cols))) {}
    matrix(size_t rows, size_t cols, T defaultValue): data(vector<vector<T>>(rows, vector<T>(cols, defaultValue))) {}
    matrix(size_t rows, size_t cols, function<T(size_t, size_t)> &&generator): matrix(rows, cols) {
        repeat(i, rows) {
            repeat(j, cols) {
                data[i][j] = generator(i, j);
            }
        }
    }

    [[nodiscard]] size_t rows() const {
        return data.size();
    }

    [[nodiscard]] size_t cols() const {
        return data[0].size();
    }

    [[nodiscard]] bool inBounds(size_t i, size_t j) const {
        return i >= 0 && i < rows() && j >= 0 && j < cols();
    }

    vector<T>& operator[](size_t i) {
        return data[i];
    }

    const vector<T>& operator[](size_t i) const {
        return data[i];
    }

    T& operator[](pair<size_t, size_t> index) {
        return data[index.first][index.second];
    }
    
    T operator()(size_t i, size_t j) const {
        return data[i][j];
    }
};