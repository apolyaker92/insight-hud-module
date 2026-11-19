// PH-1 HUD mount: dock, slider, tray and front frame.
// USB-C faces +X. Dimensions are in millimeters.
part = "quick_dock"; // [quick_dock, slider, tray, frame, quick_assembly]
lean_back = 15;
dash_drop_right = 8;

phone_length = 141.5;
phone_width = 71.1;
phone_thickness = 7.8;
bay_length = phone_length + 0.9;
bay_width = phone_width + 1.0;
face_clearance = 0.6;
end_overlap = 2.5;
usb_width = 24;
base_thickness = 4.6;
frame_thickness = 3.2;
frame_z = base_thickness + phone_thickness + face_clearance;

outer_length = 163;
outer_width = 83;
frame_bolt_x = 76.5;
frame_bolt_y = 36.5;
foot_bolt_x = 54;
foot_bolt_y = -25;
m3_clearance = 3.4;
nut_width = 6.0; // Across flats, including print clearance.
nut_entry_width = 6.4;
nut_entry_depth = 0.45;
nut_depth = 2.8;
eps = 0.02;
$fn = 48;

module outline() {
    offset(r = 4) square([outer_length - 8, outer_width - 8], center = true);
}

module usb_notch() {
    translate([phone_length / 2 - 5, -usb_width / 2])
        square([outer_length / 2 - phone_length / 2 + 6, usb_width]);
}

module frame_bolts() {
    for (x = [-frame_bolt_x, frame_bolt_x], y = [-frame_bolt_y, frame_bolt_y])
        translate([x, y, 0]) children();
}

module foot_bolts() {
    for (x = [-foot_bolt_x, foot_bolt_x])
        translate([x, foot_bolt_y, 0]) children();
}

// The pocket opens at Z=0 and cuts inward along +Z.
module nut_pocket() {
    translate([0, 0, -eps]) {
        cylinder(h = nut_depth + eps, r = nut_width / sqrt(3), $fn = 6);
        cylinder(h = nut_entry_depth + eps, r1 = nut_entry_width / sqrt(3),
            r2 = nut_width / sqrt(3), $fn = 6);
    }
}

module tray() {
    difference() {
        union() {
            linear_extrude(base_thickness)
                difference() {
                    outline();
                    offset(r = 4) square([100, 31], center = true);
                }

            // End stops and short corner guides leave the side buttons open.
            translate([0, 0, base_thickness - eps])
                linear_extrude(frame_z - base_thickness + eps)
                    difference() {
                        intersection() {
                            outline();
                            for_end_guides();
                        }
                        square([bay_length, bay_width], center = true);
                    }
        }

        translate([0, 0, -eps]) linear_extrude(frame_z + 2 * eps) usb_notch();

        frame_bolts() {
            translate([0, 0, -eps]) cylinder(h = frame_z + 2 * eps, d = m3_clearance);
            nut_pocket();
        }

        foot_bolts() {
            translate([0, 0, -eps]) cylinder(h = base_thickness + 2 * eps, d = m3_clearance);
            // Heads sit below the phone's rear surface.
            translate([0, 0, base_thickness - 2.7]) cylinder(h = 2.7 + eps, d = 6.2);
        }
    }
}

module for_end_guides() {
    for (side = [-1, 1])
        translate([side * 73, 0]) square([24, outer_width + 2], center = true);
}

module frame() {
    linear_extrude(frame_thickness)
        difference() {
            outline();
            // The aperture clears the long edges and captures the short bezels.
            square([phone_length - 2 * end_overlap, bay_width + 2.6], center = true);
            usb_notch();
            frame_bolts() circle(d = m3_clearance);
        }
}

slider_low = -35;
slider_high = -17;
slider_plate = 3;
slider_depth = 10;
slide_clearance = 0.45;
pin_y = -31.5;
pin_z = 6.5;
pin_bore = 3.6;

// Positive Z points away from the tray in the slider's print orientation.
module dovetail(x, start, end, clearance = 0) {
    translate([x, end, 0]) rotate([90, 0, 0])
        linear_extrude(end - start)
            polygon([
                [-5 - clearance, slider_plate - clearance],
                [5 + clearance, slider_plate - clearance],
                [8.5 + clearance, slider_depth + clearance],
                [-8.5 - clearance, slider_depth + clearance]
            ]);
}

module pin_hole() {
    translate([foot_bolt_x - 13, pin_y, pin_z])
        rotate([0, 90, 0]) cylinder(h = 26, d = pin_bore);
}

module slider() {
    difference() {
        union() {
            translate([-65, slider_low, 0])
                cube([130, slider_high - slider_low, slider_plate]);
            for (x = [-foot_bolt_x, foot_bolt_x])
                translate([0, 0, -eps]) dovetail(x, slider_low, slider_high);
        }
        foot_bolts() {
            translate([0, 0, -eps]) cylinder(h = slider_depth + 2 * eps, d = m3_clearance);
            translate([0, 0, slider_depth]) mirror([0, 0, 1]) nut_pocket();
        }
        pin_hole();
    }
}

foot_width = 134;
sole_angle = atan(dash_drop_right / foot_width);

module cradle_pose() {
    angle = 90 - lean_back;
    translate([0, -12, 4.8 + sin(angle) * outer_width / 2])
        rotate([angle, 0, 0]) children();
}

module sole() {
    intersection() {
        translate([0, 2, -dash_drop_right - eps])
            linear_extrude(4 + dash_drop_right + eps)
                offset(r = 5) square([foot_width - 10, 50], center = true);

        // Keep the top level with the cradle while matching the dash below.
        translate([0, 0, -dash_drop_right / 2])
            rotate([0, sole_angle, 0])
                translate([-foot_width, -80, 0])
                    cube([2 * foot_width, 160, 50]);
    }
}

module quick_dock() {
    rib_front = max(-19, -12 - sin(lean_back) * outer_width / 2 + 3);
    difference() {
        union() {
            sole();
            for (x = [-foot_bolt_x, foot_bolt_x])
                hull() {
                    translate([x - 11, rib_front, 3.8]) cube([22, 29 - rib_front, 0.2]);
                    cradle_pose()
                        translate([x - 11, -39, -15]) cube([22, 26, 12]);
                }
        }
        cradle_pose() mirror([0, 0, 1]) {
            for (x = [-foot_bolt_x, foot_bolt_x])
                dovetail(x, slider_low - 0.15, 40, slide_clearance);
            pin_hole();
        }
    }
}

module printable_quick_dock() {
    rotate([0, -sole_angle, 0])
        translate([0, 0, dash_drop_right / 2]) quick_dock();
}

if (part == "quick_dock") printable_quick_dock();
if (part == "slider") slider();
if (part == "tray") tray();
if (part == "frame") frame();
if (part == "quick_assembly") {
    color("#4b5563") quick_dock();
    cradle_pose() {
        color("#21988c") mirror([0, 0, 1]) slider();
        color("#374151") tray();
        color("#101820")
            translate([-phone_length / 2, -phone_width / 2, base_thickness])
                cube([phone_length, phone_width, phone_thickness]);
        color("#8298a8") translate([0, 0, frame_z]) frame();
    }
}
