# Mod Sub-Grid & Off-Grid Micro-Architecture v1.0.0 (Minecraft 1.20.1 Fabric)

Permite colocar cualquier bloque de tamano completo en grillas de **1/2**, **1/4**, **1/8** y **Modo Secreto Off-Grid (1/16)** en los 3 ejes (X, Y, Z) como bloques reales con hitboxes reales, combinacion de Slabs y Escaleras, Redstone inteligente y Pistones adaptativos.

## Controles y Mecanicas
- **Tecla `G`:** Achica el tamano de la Grid (`1/1` -> `1/2` -> `1/4` -> `1/8`).
- **Tecla `H`:** Agranda el tamano de la Grid (`1/8` -> `1/4` -> `1/2` -> `1/1`).
- **Modo Secreto OFF-GRID (`Shift + G` estando en `1/8`, o `G + H` juntas):** Colocacion libre al milimetro (`1/16`) en cualquier punto de los 3 ejes.
- **Combinacion de Slabs y Escaleras:** Podes poner una Slab de madera encima de una de piedra en el mismo bloque, o encajar una escalera invertida boca abajo (`Shift` o apuntando arriba) con una escalera normal.
- **Utilidad Funcional de cambiar la Grid en Redstone:**
  - **Grid 1/2:** Conduccion de potencia completa (15) y bloques compuestos (ej. Vidrio + Piedra Luminosa = Vidrio luminoso nivel 15; Slime + Redstone = Catapulta de Fase).
  - **Grid 1/4:** Diodo direccional compacto (solo emite Redstone hacia el lado desplazado).
  - **Grid 1/8:** Micro-Memoria T-Flip-Flop (cualquier boton colocado en Grid 1/8 se comporta como un interruptor permanente ON/OFF).
- **Pistones Adaptativos y Motor Lineal (`subgrid_actuator`):** Los pistones empujan y retraen bloques de Sub-Grid de a pasos del tamano de su Grid.
