# Mod Sub-Grid & Off-Grid Micro-Architecture v2.0.0 (Minecraft 1.20.1 Fabric)

Permite colocar cualquier bloque original de Minecraft con todas sus propiedades nativas (colisiones reales, color de bioma, gravedad, conexiones de cristales/vallas, fuego y redstone) en grillas de **1/1**, **1/2**, **1/4**, **1/8** y **Modo Secreto Off-Grid (1/16)** con rotacion libre en el eje Y.

## Novedades de la v2.0.0
- **Delegacion Universal a las Propiedades Originales del Bloque:**
  - **Colisiones Nativas:** Caña de azucar, pasto, flores, antorchas y fuego no tienen colision (`getCollisionShape` original), mientras que vallas, paneles, losas y escaleras usan su colision exacta.
  - **Color de Bioma Real:** Pasto, cesped, helechos, caña de azucar, hojas, enredaderas y agua toman automaticamente el color exacto del bioma donde los pones.
  - **Contorno Completo sin Recortar:** Al apuntar con la mano vacia a un bloque desplazado en otra grid, el borde negro nativo marca el bloque entero 1x1x1 sin cortarlo en el limite de la celda.
- **Rotacion Libre en el Eje Y en Modo Off-Grid (`1/16`):** Tanto la previsualizacion sutil como el bloque colocado rotan libremente en el eje Y siguiendo tu angulo de camara.
- **Gravedad Real en Sub-Grid y Off-Grid:** La arena, grava, polvo de hormigon y yunques caen con gravedad respetando su posicion de Sub-Grid/Off-Grid y su rotacion Y.
- **Sin Auto-Jump Forzado y Sin Colocar Bloques Donde Estas Parado.**
- **Cristales Conectados, Proteccion contra Agua (sin hitboxes fantasmas) y Soporte de Fuego/Mechero.**
