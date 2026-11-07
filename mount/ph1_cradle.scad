// PH-1 HUD cradle. Dimensions in millimeters. The USB-C end faces +X.
// Print one cradle and two keepers. Use two M3 x 8 mm screws and M3 hex nuts.
part = "cradle"; // [cradle, keeper, plate, assembly]

phone_length = 141.5;
phone_width = 71.1;
phone_thickness = 7.8;
end_clearance = 0.8;
side_clearance = 0.45; // Per side.
top_clearance = 0.5;

base = 3.6;
wall = 3;
rail_length = 16;
lip = 1.3;
roof = 1.6;
end_land = 11;
keeper_diameter = 10;
keeper_height = 4.6;
bolt_hole = 3.4;
nut_width = 5.7; // Across flats. Includes clearance for a nominal 5.5 mm M3 nut.
nut_depth = 2.6;

bay_length = phone_length + end_clearance;
half_bay = phone_width / 2 + side_clearance;
stop_x = bay_length + keeper_diameter / 2;
stop_y = half_bay - 6.5;
eps = 0.02;
$fn = 48;

module rounded_rectangle(x, y, length, width, radius) {
    translate([x + radius, y + radius])
        offset(r = radius) square([length - 2 * radius, width - 2 * radius]);
}

module rail(x) {
    clearance_z = base + phone_thickness + top_clearance;
    translate([x, half_bay, base - eps])
        cube([rail_length, wall, clearance_z + lip + roof - base + eps]);

    // The underside rises at 45 degrees toward the display edge.
    hull() {
        translate([x, half_bay, clearance_z])
            cube([rail_length, eps, lip + roof]);
        translate([x, half_bay - lip, clearance_z + lip])
            cube([rail_length, eps, roof]);
    }
}

module cradle() {
    difference() {
        union() {
            linear_extrude(base)
                rounded_rectangle(-wall, -half_bay - wall,
                    bay_length + wall + end_land, 2 * (half_bay + wall), 3);

            // Retain the flat top edge opposite the USB-C connector.
            translate([-wall, -half_bay + 3, base - eps])
                cube([wall + eps, 2 * half_bay - 6, 6.5]);

            // Short corner rails leave both long sides open around the buttons.
            for (x = [0, bay_length - rail_length]) {
                rail(x);
                mirror([0, 1, 0]) rail(x);
            }
        }

        translate([0, 0, -eps])
            linear_extrude(base + 2 * eps)
                rounded_rectangle(18, -half_bay + 11,
                    bay_length - 36, 2 * half_bay - 22, 4);

        for (side = [-1, 1]) {
            translate([stop_x, side * stop_y, -eps]) {
                cylinder(h = base + 2 * eps, d = bolt_hole);
                cylinder(h = nut_depth + eps, r = nut_width / sqrt(3), $fn = 6);
            }
        }
    }
}

module keeper() {
    difference() {
        cylinder(h = keeper_height, d = keeper_diameter);
        translate([0, 0, -eps]) cylinder(h = keeper_height + 2 * eps, d = bolt_hole);
    }
}

if (part == "cradle") cradle();
if (part == "keeper") keeper();
if (part == "plate") {
    cradle();
    // Nest both keepers in the opening, flat on the same build plane.
    for (fraction = [0.4, 0.6])
        translate([bay_length * fraction, 0, 0]) keeper();
}
if (part == "assembly") {
    color([0.23, 0.26, 0.28]) cradle();
    for (side = [-1, 1])
        color([0.90, 0.56, 0.16]) translate([stop_x, side * stop_y, base]) keeper();
}
