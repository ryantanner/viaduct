import unittest
import sys
from pathlib import Path

# Add parent directory to path so we can import the module
sys.path.insert(0, str(Path(__file__).parent.parent))

from generate_changelog import extract_username_from_email, extract_username, format_entry, clean_commit_message, should_include_entry


class TestShouldIncludeEntry(unittest.TestCase):
    def test_includes_normal_commit(self):
        entry = "Fix bug in parser by AUTHOR_STARTjohn.doe@example.comAUTHOR_END CO_AUTHORS_STARTCO_AUTHORS_END"
        self.assertTrue(should_include_entry(entry))

    def test_includes_feat_commit(self):
        entry = "feat: add new feature by AUTHOR_STARTjohn.doe@example.comAUTHOR_END CO_AUTHORS_STARTCO_AUTHORS_END"
        self.assertTrue(should_include_entry(entry))

    def test_excludes_ignore_lowercase(self):
        entry = "ignore: test commit by AUTHOR_STARTjohn.doe@example.comAUTHOR_END CO_AUTHORS_STARTCO_AUTHORS_END"
        self.assertFalse(should_include_entry(entry))

    def test_excludes_ignore_uppercase(self):
        entry = "IGNORE: experimental change by AUTHOR_STARTjohn.doe@example.comAUTHOR_END CO_AUTHORS_STARTCO_AUTHORS_END"
        self.assertFalse(should_include_entry(entry))

    def test_excludes_ignore_mixed_case(self):
        entry = "Ignore: testing feature by AUTHOR_STARTjohn.doe@example.comAUTHOR_END CO_AUTHORS_STARTCO_AUTHORS_END"
        self.assertFalse(should_include_entry(entry))

    def test_includes_commit_with_ignore_in_middle(self):
        entry = "Fix: ignore whitespace in parser by AUTHOR_STARTjohn.doe@example.comAUTHOR_END CO_AUTHORS_STARTCO_AUTHORS_END"
        self.assertTrue(should_include_entry(entry))

    def test_handles_entry_without_author_marker(self):
        entry = "Some commit without proper format"
        self.assertTrue(should_include_entry(entry))

    def test_includes_commit_with_ignore_after_description(self):
        entry = "feat: new feature (ignore old implementation) by AUTHOR_STARTjohn.doe@example.comAUTHOR_END CO_AUTHORS_STARTCO_AUTHORS_END"
        self.assertTrue(should_include_entry(entry))


class TestCleanCommitMessage(unittest.TestCase):
    def test_removes_github_change_id(self):
        message = "Fix bug Github-Change-Id: 956283"
        result = clean_commit_message(message)
        self.assertEqual(result, "Fix bug")
        self.assertNotIn("Github-Change-Id", result)

    def test_removes_gitorigin_revid(self):
        message = "Update docs GitOrigin-RevId: 1fcdd8123bc49a717103985430322eca3b5b1fb3"
        result = clean_commit_message(message)
        self.assertEqual(result, "Update docs")
        self.assertNotIn("GitOrigin-RevId", result)

    def test_keeps_closes_issue_reference(self):
        message = "Fix parser Closes #137"
        result = clean_commit_message(message)
        self.assertEqual(result, "Fix parser Closes #137")
        self.assertIn("Closes #137", result)

    def test_keeps_fixes_issue_reference(self):
        message = "Fix bug Fixes #456"
        result = clean_commit_message(message)
        self.assertEqual(result, "Fix bug Fixes #456")
        self.assertIn("Fixes #456", result)

    def test_removes_multiple_metadata_patterns(self):
        message = "Feature Github-Change-Id: 123 GitOrigin-RevId: abc123"
        result = clean_commit_message(message)
        self.assertEqual(result, "Feature")
        self.assertNotIn("Github-Change-Id", result)
        self.assertNotIn("GitOrigin-RevId", result)

    def test_mixed_metadata_and_issue_reference(self):
        message = "Build docs Closes #137 Github-Change-Id: 956283"
        result = clean_commit_message(message)
        self.assertEqual(result, "Build docs Closes #137")
        self.assertIn("Closes #137", result)
        self.assertNotIn("Github-Change-Id", result)

    def test_case_insensitive_matching(self):
        message = "Fix GITHUB-CHANGE-ID: 789"
        result = clean_commit_message(message)
        self.assertNotIn("GITHUB-CHANGE-ID", result)


class TestExtractUsernameFromEmail(unittest.TestCase):
    def test_valid_email(self):
        self.assertEqual(extract_username_from_email("john.doe@example.com"), "@john.doe")

    def test_email_with_plus(self):
        self.assertEqual(extract_username_from_email("john+test@example.com"), "@john+test")

    def test_empty_email(self):
        self.assertEqual(extract_username_from_email(""), "")

    def test_noreply_email(self):
        self.assertEqual(extract_username_from_email("noreply@github.com"), "")

    def test_no_reply_email(self):
        self.assertEqual(extract_username_from_email("no-reply@github.com"), "")

    def test_github_actions_email(self):
        self.assertEqual(extract_username_from_email("github-actions@github.com"), "")

    def test_viaductbot_email(self):
        self.assertEqual(extract_username_from_email("viaductbot@airbnb.com"), "")

    def test_invalid_email_no_at(self):
        self.assertEqual(extract_username_from_email("notanemail"), "")

    def test_email_with_dash(self):
        self.assertEqual(extract_username_from_email("john-doe@example.com"), "@john-doe")

    def test_email_with_underscore(self):
        self.assertEqual(extract_username_from_email("john_doe@example.com"), "@john_doe")


class TestExtractUsername(unittest.TestCase):
    def test_valid_co_author_line(self):
        self.assertEqual(
            extract_username("John Doe <john.doe@example.com>"),
            "@john.doe"
        )

    def test_co_author_with_full_name(self):
        self.assertEqual(
            extract_username("Jane Smith <jane.smith@company.com>"),
            "@jane.smith"
        )

    def test_noreply_co_author(self):
        self.assertEqual(
            extract_username("GitHub <noreply@github.com>"),
            ""
        )

    def test_github_actions_co_author(self):
        self.assertEqual(
            extract_username("Actions Bot <github-actions@github.com>"),
            ""
        )

    def test_viaductbot_co_author(self):
        self.assertEqual(
            extract_username("Viaduct Bot <viaductbot@airbnb.com>"),
            ""
        )

    def test_invalid_format_no_email(self):
        self.assertEqual(extract_username("John Doe"), "")

    def test_email_only(self):
        self.assertEqual(
            extract_username("<alice@example.com>"),
            "@alice"
        )

    def test_co_author_with_plus_in_email(self):
        self.assertEqual(
            extract_username("Bob <bob+test@example.com>"),
            "@bob+test"
        )


class TestFormatEntry(unittest.TestCase):
    def test_single_author_no_coauthors(self):
        entry = "SHA_STARTabc123SHA_END Fix bug in parser by AUTHOR_STARTjohn.doe@example.comAUTHOR_END CO_AUTHORS_STARTCO_AUTHORS_END"
        result = format_entry(entry)
        self.assertEqual(result, "Fix bug in parser by @john.doe")

    def test_author_with_single_coauthor(self):
        entry = "SHA_STARTabc123SHA_END Add new feature by AUTHOR_STARTjohn.doe@example.comAUTHOR_END CO_AUTHORS_STARTJane Smith <jane.smith@example.com>CO_AUTHORS_END"
        result = format_entry(entry)
        self.assertEqual(result, "Add new feature by @john.doe, @jane.smith")

    def test_author_with_multiple_coauthors(self):
        entry = "SHA_STARTabc123SHA_END Refactor code by AUTHOR_STARTalice@example.comAUTHOR_END CO_AUTHORS_STARTBob <bob@example.com>|Charlie <charlie@example.com>CO_AUTHORS_END"
        result = format_entry(entry)
        self.assertEqual(result, "Refactor code by @alice, @bob, @charlie")

    def test_bot_author_filtered_out(self):
        entry = "SHA_STARTabc123SHA_END Update dependencies by AUTHOR_STARTnoreply@github.comAUTHOR_END CO_AUTHORS_STARTCO_AUTHORS_END"
        result = format_entry(entry)
        self.assertEqual(result, "Update dependencies by @anonymous")

    def test_bot_coauthor_filtered_out(self):
        entry = "SHA_STARTabc123SHA_END Merge PR by AUTHOR_STARTjohn@example.comAUTHOR_END CO_AUTHORS_STARTGitHub Actions <github-actions@github.com>CO_AUTHORS_END"
        result = format_entry(entry)
        self.assertEqual(result, "Merge PR by @john")

    def test_all_bots_filtered_out(self):
        entry = "SHA_STARTabc123SHA_END Auto update by AUTHOR_STARTnoreply@github.comAUTHOR_END CO_AUTHORS_STARTBot <viaductbot@airbnb.com>CO_AUTHORS_END"
        result = format_entry(entry)
        self.assertEqual(result, "Auto update by @anonymous")

    def test_empty_coauthors_segment(self):
        entry = "SHA_STARTabc123SHA_END Simple commit by AUTHOR_STARTdev@example.comAUTHOR_END CO_AUTHORS_STARTCO_AUTHORS_END"
        result = format_entry(entry)
        self.assertEqual(result, "Simple commit by @dev")

    def test_commit_message_with_special_chars(self):
        entry = "SHA_STARTabc123SHA_END Fix: handle [special] chars (issue #123) by AUTHOR_STARTuser@example.comAUTHOR_END CO_AUTHORS_STARTCO_AUTHORS_END"
        result = format_entry(entry)
        self.assertEqual(result, "Fix: handle [special] chars (issue #123) by @user")

    def test_mixed_valid_and_invalid_coauthors(self):
        entry = "SHA_STARTabc123SHA_END Team effort by AUTHOR_STARTlead@example.comAUTHOR_END CO_AUTHORS_STARTDev1 <dev1@example.com>|Bot <noreply@github.com>|Dev2 <dev2@example.com>CO_AUTHORS_END"
        result = format_entry(entry)
        self.assertEqual(result, "Team effort by @lead, @dev1, @dev2")

    def test_coauthor_with_empty_entries(self):
        entry = "SHA_STARTabc123SHA_END Update docs by AUTHOR_STARTauthor@example.comAUTHOR_END CO_AUTHORS_START|Helper <helper@example.com>|CO_AUTHORS_END"
        result = format_entry(entry)
        self.assertEqual(result, "Update docs by @author, @helper")

    def test_commit_with_metadata_in_subject(self):
        """
        Test that exposes the issue where commits without proper blank line after subject
        include metadata (Github-Change-Id, Closes, etc.) in the changelog entry.

        This happens because git's %s format joins multi-line subjects with spaces.
        Metadata should be filtered out from the changelog.
        """
        entry = "SHA_STARTabc123SHA_END Build from scratch docs improvement Closes #137 Github-Change-Id: 956283 by AUTHOR_STARTuser@example.comAUTHOR_END CO_AUTHORS_STARTCO_AUTHORS_END"
        result = format_entry(entry)

        # Assert that metadata is NOT in the result
        self.assertNotIn("Github-Change-Id", result)
        self.assertIn("Closes #137", result)
        self.assertIn("Build from scratch docs improvement", result)
        self.assertIn("@user", result)

    def test_commit_with_gitorigin_revid_in_subject(self):
        """
        Test for filtering out GitOrigin-RevId metadata from changelog entries.
        """
        entry = "SHA_STARTabc123SHA_END Fix parser bug GitOrigin-RevId: 1fcdd8123bc49a717103985430322eca3b5b1fb3 by AUTHOR_STARTdev@example.comAUTHOR_END CO_AUTHORS_STARTCO_AUTHORS_END"
        result = format_entry(entry)

        # Assert that GitOrigin-RevId is NOT in the result
        self.assertNotIn("GitOrigin-RevId", result)
        self.assertIn("Fix parser bug", result)
        self.assertIn("@dev", result)

    def test_replaces_airbnb_with_sha_lowercase(self):
        """Test that (AIRBNB) is replaced with the commit SHA (lowercase)."""
        entry = "SHA_STARTabc123SHA_END Fix bug (airbnb) by AUTHOR_STARTdev@example.comAUTHOR_END CO_AUTHORS_STARTCO_AUTHORS_END"
        result = format_entry(entry)
        self.assertIn("(abc123)", result)
        self.assertNotIn("(airbnb)", result.lower())
        self.assertIn("Fix bug", result)

    def test_replaces_airbnb_with_sha_uppercase(self):
        """Test that (AIRBNB) is replaced with the commit SHA (uppercase)."""
        entry = "SHA_STARTdef456SHA_END Update feature (AIRBNB) by AUTHOR_STARTuser@example.comAUTHOR_END CO_AUTHORS_STARTCO_AUTHORS_END"
        result = format_entry(entry)
        self.assertIn("(def456)", result)
        self.assertNotIn("(AIRBNB)", result)
        self.assertIn("Update feature", result)

    def test_replaces_airbnb_with_sha_mixed_case(self):
        """Test that (AirBnB) is replaced with the commit SHA (mixed case)."""
        entry = "SHA_START789abcSHA_END Add new endpoint (AirBnB) by AUTHOR_STARTdev@example.comAUTHOR_END CO_AUTHORS_STARTCO_AUTHORS_END"
        result = format_entry(entry)
        self.assertIn("(789abc)", result)
        self.assertNotIn("AirBnB", result)
        self.assertIn("Add new endpoint", result)

    def test_commit_without_airbnb_marker(self):
        """Test that commits without (AIRBNB) work normally."""
        entry = "SHA_START123456SHA_END Fix normal bug by AUTHOR_STARTdev@example.comAUTHOR_END CO_AUTHORS_STARTCO_AUTHORS_END"
        result = format_entry(entry)
        self.assertIn("Fix normal bug", result)
        self.assertIn("@dev", result)
        self.assertNotIn("(123456)", result)  # SHA should NOT appear unless (AIRBNB) was present


if __name__ == '__main__':
    unittest.main()
