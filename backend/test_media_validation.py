import unittest

from backend.media_validation import MediaLimitError, parse_probe


class MediaValidationTest(unittest.TestCase):
    def test_accepts_bounded_video(self):
        metadata = parse_probe({
            "format": {"duration": "12.5"},
            "streams": [{"width": 1280, "height": 720, "nb_read_frames": "313"}],
        })
        self.assertEqual(1280, metadata.width)
        self.assertEqual(313, metadata.frames)

    def test_rejects_duration_limit(self):
        with self.assertRaisesRegex(MediaLimitError, "duration"):
            parse_probe({
                "format": {"duration": "30.1"},
                "streams": [{"width": 1280, "height": 720, "nb_read_frames": "753"}],
            })

    def test_rejects_resolution_limit(self):
        with self.assertRaisesRegex(MediaLimitError, "resolution"):
            parse_probe({
                "format": {"duration": "10"},
                "streams": [{"width": 3840, "height": 2160, "nb_read_frames": "300"}],
            })

    def test_rejects_frame_limit(self):
        with self.assertRaisesRegex(MediaLimitError, "frames"):
            parse_probe({
                "format": {"duration": "30"},
                "streams": [{"width": 1280, "height": 720, "nb_read_frames": "1801"}],
            })

    def test_rejects_missing_or_invalid_metadata(self):
        for value in ({}, {"format": {"duration": "nan"}, "streams": [{}]}):
            with self.subTest(value=value), self.assertRaises(MediaLimitError):
                parse_probe(value)


if __name__ == "__main__":
    unittest.main()
