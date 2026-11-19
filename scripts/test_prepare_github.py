import unittest
from pathlib import Path
from prepare_github import ROOT, inspect_text, private_reason, missing_doc_links


class PublishInventoryTest(unittest.TestCase):
    def test_secrets_and_generated_files_are_excluded_but_build_inputs_remain(self):
        for path in ("app/app.key", "app/google-services.json", "web-ui/.env.production", "docs/local/x.md", "node_modules/pkg/index.js", "x.hprof"):
            self.assertIsNotNone(private_reason(path), path)
        for path in ("web-ui/.env.example", "gradle/wrapper/gradle-wrapper.jar", "third_party/lib.aar", "app/schemas/1.json"):
            self.assertIsNone(private_reason(path), path)

    def test_reports_find_credentials_without_returning_their_values(self):
        secret = "ghp_" + "x" * 36
        hits = inspect_text("heading\n" + secret)
        self.assertEqual([{"rule": "github-token", "line": 2}], hits)
        self.assertNotIn(secret, str(hits))
        self.assertTrue(inspect_text("https://account:" + "password@service.vendor.dev/api"))

    def test_key_parsing_and_reserved_url_test_fixtures_are_not_credentials(self):
        self.assertEqual([], inspect_text('replace("-----BEGIN PRIVATE KEY-----", "")'))
        self.assertEqual([], inspect_text("https://user:pass@example.test/path"))
        block = "-----BEGIN PRIVATE KEY-----\n" + "A" * 128 + "\n-----END PRIVATE KEY-----"
        self.assertTrue(inspect_text(block))

    def test_build_links_remain_private_even_if_the_local_build_exists(self):
        self.assertEqual(["../build/reports"], missing_doc_links(ROOT / "docs/README.md", "[private](../build/reports)"))

    def test_local_links_are_checked_but_example_code_and_web_links_are_skipped(self):
        path = ROOT / "docs" / "README.md"
        source = '[ok](../LICENSE) [missing](does-not-exist.md) [web](https://example.test)\n```text\n[example](example.md)\n```'
        self.assertEqual(["does-not-exist.md"], missing_doc_links(path, source))


if __name__ == "__main__":
    unittest.main()
