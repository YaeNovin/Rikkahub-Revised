import unittest
from normalize_source_endings import normalize


class SourceEndingsTest(unittest.TestCase):
    def test_preserves_markdown_break_spaces_and_final_newline_state(self):
        self.assertEqual(b"one  \ntwo\nlast", normalize(b"one  \r\ntwo\r\nlast"))

    def test_does_not_corrupt_binary_or_non_utf8_data(self):
        for data in (b"PNG\0\r\n", b"\xff\xfe\r\n"):
            self.assertEqual(data, normalize(data))

    def test_preserves_utf8_bom_and_literal_escaped_sequences(self):
        self.assertEqual(b'\xef\xbb\xbf"\\r\\n"\n', normalize(b'\xef\xbb\xbf"\\r\\n"\r\n'))


if __name__ == "__main__":
    unittest.main()
