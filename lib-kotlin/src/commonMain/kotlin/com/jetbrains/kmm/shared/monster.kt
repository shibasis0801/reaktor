package com.jetbrains.kmm.shared

enum class Color(val value: Byte) {
    Red(0),
    Green(1),
    Blue(2)
}

typealias Equipment = Weapon
// If more types are added, use a sealed class.

data class Vec3(val x: Float, val y: Float, val z: Float)

data class Monster(
    val pos: Vec3,                    // Struct.
    val mana: Short = 150,            // Optional with default value.
    val hp: Short = 100,              // Optional with default value.
    val name: String,
    val friendly: Boolean = false,    // Deprecated, optional.
    val inventory: List<Byte>,        // Vector of numbers (bytes).
    val color: Color = Color.Blue,    // Optional with default value.
    val weapons: List<Weapon>,        // Vector of tables (Weapon).
    val equipped: Equipment,          // Union.
    val path: List<Vec3>              // Vector of structs (Vec3).
)
data class Weapon(
    val name: String,
    val damage: Short
)
