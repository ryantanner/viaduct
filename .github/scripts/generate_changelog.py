#!/usr/bin/env python3
import argparse
import re
import subprocess
from semantic_release.commit_parser.conventional import ConventionalCommitParser
from semantic_release.changelog.context import make_changelog_context

# Generate conventional changelog between two git refs
# Usage: python generate_changelog.py <commit1> <commit2>
# Example: python generate_changelog.py v1.0.0 v1.1.0
# This will output the changelog entries between the two commits, including co-authors formatted as GitHub usernames.
def main():
  parser = argparse.ArgumentParser(description='Generate changelog between two git refs.')
  parser.add_argument('commit1', help='First git ref')
  parser.add_argument('commit2', help='Second git ref')
  args = parser.parse_args()

  context = make_changelog_context()

  git_cmd = [
    'git', 'log',
    f'{args.commit1}..{args.commit2}',
    '--format=format:SHA_START%hSHA_END %s by AUTHOR_START%aeAUTHOR_END CO_AUTHORS_START%(trailers:key=Co-authored-by,valueonly,separator=%x7C)CO_AUTHORS_END'
  ]

  result = subprocess.run(git_cmd, capture_output=True, text=True)

  entries = result.stdout.splitlines()

  filtered_entries = [entry for entry in entries if should_include_entry(entry)]

  formatted_entries = [format_entry(entry) for entry in filtered_entries]

  changelog = "\n".join(formatted_entries)

  print(changelog)

def should_include_entry(entry: str) -> bool:
  commit_message_end = entry.find(' by AUTHOR_START')
  if commit_message_end == -1:
    return True

  commit_message = entry[:commit_message_end].strip()
  return not commit_message.lower().startswith('ignore:')

def clean_commit_message(message: str) -> str:
  # Remove internal metadata patterns but keep "Closes #123", "Fixes #456", etc.
  patterns = [
    r'\s+Github-Change-Id:\s+\w+',
    r'\s+GitOrigin-RevId:\s+[a-f0-9]+',
  ]

  cleaned = message
  for pattern in patterns:
    cleaned = re.sub(pattern, '', cleaned, flags=re.IGNORECASE)

  return cleaned.strip()

def format_entry(entry):
  # Extract and remove SHA from entry
  sha_start_idx = entry.find('SHA_START')
  sha_end_idx = entry.find('SHA_END')
  commit_sha = entry[sha_start_idx + len('SHA_START'):sha_end_idx].strip()
  # Remove SHA markers and the space after SHA_END from entry
  entry = entry[:sha_start_idx] + entry[sha_end_idx + len('SHA_END '):]

  # Extract commit author
  author_start_idx = entry.find('AUTHOR_START') + 'AUTHOR_START'.__len__()
  author_end_idx = entry.find('AUTHOR_END')
  commit_author_email = entry[author_start_idx:author_end_idx].strip()

  # Extract co-authors
  co_author_start_idx = entry.find('CO_AUTHORS_START') + 'CO_AUTHORS_START'.__len__()
  co_author_end_idx = entry.find('CO_AUTHORS_END')
  co_authors_segment = entry[co_author_start_idx:co_author_end_idx].strip()

  # Build list of all authors
  usernames = []

  # Add commit author
  commit_author_username = extract_username_from_email(commit_author_email)
  if commit_author_username:
    usernames.append(commit_author_username)

  # Add co-authors if present
  if co_authors_segment:
    co_authors = co_authors_segment.split('|')
    co_author_usernames = [extract_username(author_str) for author_str in co_authors if author_str.strip()]
    usernames.extend([u for u in co_author_usernames if u])

  # Get commit message part and clean metadata
  commit_info = entry[:entry.find(' by AUTHOR_START')].strip()
  commit_info = clean_commit_message(commit_info)

  # Replace (AIRBNB) with commit SHA
  commit_info = re.sub(r'\(AIRBNB\)', f'({commit_sha})', commit_info, flags=re.IGNORECASE)

  # Format output
  if usernames:
    return commit_info + ' by ' + ', '.join(usernames)
  else:
    return commit_info + ' by @anonymous'

def extract_username_from_email(email: str) -> str:
  """Extract username from email address."""
  if not email:
    return ""
  match = re.search(r'^([^@]+)@', email)
  if match:
    username = match.group(1)
    # Filter out common bot/system accounts
    if username not in ['noreply', 'no-reply', 'github-actions', 'viaductbot']:
      return "@" + username
  return ""

def extract_username(author_line: str) -> str:
  """Extract username from Co-authored-by format: Name <email>"""
  match = re.search(r'<([^@]+)@', author_line)
  if match:
    username = match.group(1)
    # Filter out common bot/system accounts
    if username not in ['noreply', 'no-reply', 'github-actions', 'viaductbot']:
      return "@" + username
  return ""

if __name__ == '__main__':
  main()
