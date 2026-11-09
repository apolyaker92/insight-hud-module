"""Checks for the pure parts of pack_detail.py: curve finding, point features and speed parsing."""
import math
import unittest

import pack_detail as pd

MPT = 1800.0  # metres per tile, about right at Albany's latitude


def tiles(*metres):
    return [(x / MPT, y / MPT) for x, y in metres]


class CurveTests(unittest.TestCase):
    def test_straight_road_has_no_curve(self):
        road = tiles(*[(i * 40, 0) for i in range(30)])
        self.assertEqual(pd.curve_points(road, MPT), [])

    def test_sharp_corner_is_found_with_a_low_advised_speed(self):
        road = tiles(*([(i * 20, 0) for i in range(20)] + [(380, i * 20) for i in range(1, 20)]))
        found = pd.curve_points(road, MPT)
        self.assertEqual(len(found), 1)
        self.assertLessEqual(found[0][2], 30)

    def test_gentle_bend_is_ignored(self):
        road = tiles(*[(i * 40 * math.cos(i * 0.02), i * 40 * math.sin(i * 0.02)) for i in range(30)])
        self.assertEqual(pd.curve_points(road, MPT), [])

    def test_gaps_split_the_road(self):
        corner = tiles(*([(i * 20, 0) for i in range(8)] + [(140, i * 20) for i in range(1, 8)]))
        self.assertEqual(pd.curve_points(corner + [None] + corner, MPT).__len__(), 2)

    def test_wide_curve_gets_a_higher_advised_speed_than_a_tight_one(self):
        def arc(radius):
            step = 25.0 / radius
            return tiles(*[(radius * math.sin(i * step), radius * (1 - math.cos(i * step))) for i in range(int(2.2 / step))])
        tight, wide = pd.curve_points(arc(60), MPT), pd.curve_points(arc(250), MPT)
        self.assertTrue(tight)
        self.assertTrue(not wide or wide[0][2] > tight[0][2])


class FeatureTests(unittest.TestCase):
    def test_kinds(self):
        self.assertEqual(pd.point_feature({"highway": "traffic_signals"}), (pd.SIGNAL, ""))
        self.assertEqual(pd.point_feature({"highway": "stop"}), (pd.STOP, ""))
        self.assertEqual(pd.point_feature({"railway": "level_crossing"}), (pd.CROSSING, ""))
        self.assertEqual(pd.point_feature({"highway": "speed_camera"}), (pd.CAMERA, "SPEED"))
        self.assertEqual(pd.point_feature({"enforcement": "traffic_signals"}), (pd.CAMERA, "RED LIGHT"))
        self.assertEqual(pd.point_feature({"highway": "rest_area"}), (pd.REST, "REST AREA"))
        self.assertIsNone(pd.point_feature({"highway": "crossing"}))
        self.assertIsNone(pd.point_feature({"natural": "tree"}))

    def test_exit_names(self):
        self.assertEqual(pd.point_feature({"highway": "motorway_junction", "ref": "24", "name": "Washington Ave"}),
                         (pd.EXIT, "EXIT 24 / WASHINGTON AVE"))
        self.assertEqual(pd.point_feature({"highway": "motorway_junction", "ref": "7"}), (pd.EXIT, "EXIT 7"))
        self.assertIsNone(pd.point_feature({"highway": "motorway_junction"}))

    def test_speed_parsing(self):
        for value, mph in (("25 mph", 25), ("50 km/h", 30), ("signals", 0), ("none", 0), ("45 mph;25 mph", 45), (None, 0)):
            self.assertEqual(pd.parse_maxspeed(value), mph, value)


if __name__ == "__main__":
    unittest.main()
