extends Node3D

@onready var cube: MeshInstance3D = $Cube
@onready var status: Label = $Overlay/Status
@onready var cube_material: StandardMaterial3D = cube.mesh.surface_get_material(0).duplicate() as StandardMaterial3D

var speed := 0.9
var palette := [
    Color("2ec5ff"),
    Color("ff5c78"),
    Color("8b7cff"),
    Color("45e08c"),
]
var palette_index := 0


func _ready() -> void:
    cube.material_override = cube_material
    print("CLAW_IN_ONE_GODOT_READY os=%s version=%s renderer=%s" % [
        OS.get_name(),
        Engine.get_version_info()["string"],
        RenderingServer.get_current_rendering_method(),
    ])


func _process(delta: float) -> void:
    cube.rotate_y(delta * speed)
    cube.rotate_x(delta * speed * 0.32)


func _unhandled_input(event: InputEvent) -> void:
    var pressed: bool = false
    if event is InputEventScreenTouch:
        pressed = event.pressed
    elif event is InputEventMouseButton:
        pressed = event.button_index == MOUSE_BUTTON_LEFT and event.pressed
    if not pressed:
        return
    palette_index = (palette_index + 1) % palette.size()
    cube_material.albedo_color = palette[palette_index]
    speed = 0.75 + palette_index * 0.35
    status.text = "Touch confirmed · color %d/%d" % [palette_index + 1, palette.size()]
