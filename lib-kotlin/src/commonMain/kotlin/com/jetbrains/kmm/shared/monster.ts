enum Color {
  Red = 0,
  Green = 1,
  Blue = 2
}

type Equipment = Weapon;
// If more types are added, use | operator.

interface Vec3 {
  x: number;
  y: number;
  z: number;
}

interface Monster {
  pos: Vec3;                    // Struct.
  mana?: number;                // Optional with default value 150.
  hp?: number;                  // Optional with default value 100.
  name: string;
  friendly?: boolean;           // Deprecated, optional.
  inventory: number[];          // Vector of numbers (bytes).
  color?: Color;                // Optional with default value Blue.
  weapons: Weapon[];            // Vector of tables (Weapon).
  equipped: Equipment;          // Union.
  path: Vec3[];                 // Vector of structs (Vec3).
}

interface Weapon {
  name: string;
  damage: number;
}


