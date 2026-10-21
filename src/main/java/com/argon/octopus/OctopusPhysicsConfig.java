package com.argon.octopus;

/**
 * Configuración global y en tiempo real del motor biomecánico de tentáculos.
 * Modificable en vivo desde el menú interactivo (`OctopusConfigScreen` con tecla `O`).
 */
public class OctopusPhysicsConfig {
    // Cantidad de tentáculos activos (4 a 12, por defecto 8)
    public static int tentacleCount = 8;

    // Longitud total de cada tentáculo en bloques (3.5f a 11.0f, por defecto 6.5f)
    public static float tentacleLength = 6.5f;

    // Fuerza de tracción muscular y adherencia de ventosas (0.5f a 2.5f, por defecto 1.25f)
    public static float suctionPullStrength = 1.25f;

    // Peso gravitacional fuera del agua / "slump" (0.4f a 2.2f, por defecto 1.0f)
    public static float gravitySlumpFactor = 1.0f;

    // Rigidez del hidrostato muscular (0.3f = muy blando/elástico, 1.8f = firme/agresivo, por defecto 1.0f)
    public static float hydrostatStiffness = 1.0f;

    // Habilitar escalada vertical de paredes y acantilados mediante ventosas
    public static boolean wallClimbingEnabled = true;

    // Vista Rayos-X de Debug Biomecánico (muestra nodos Verlet, vectores de tracción y puntos de ventosa)
    public static boolean showDebugPhysicsVectors = false;
}
