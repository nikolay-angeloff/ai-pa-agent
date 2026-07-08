#!/usr/bin/env python3
"""
Run once to get the Gmail refresh token.

Usage:
  pip install google-auth-oauthlib
  python3 scripts/get_gmail_token.py path/to/credentials.json
"""

import json
import sys
from google_auth_oauthlib.flow import InstalledAppFlow

SCOPES = ["https://www.googleapis.com/auth/gmail.readonly"]


def main():
    creds_file = sys.argv[1] if len(sys.argv) > 1 else "credentials.json"

    flow = InstalledAppFlow.from_client_secrets_file(creds_file, SCOPES)
    creds = flow.run_local_server(port=0)

    print("\n✅  Copy these into your .env:\n")
    data = json.load(open(creds_file))["installed"]
    print(f"GMAIL_CLIENT_ID={data['client_id']}")
    print(f"GMAIL_CLIENT_SECRET={data['client_secret']}")
    print(f"GMAIL_REFRESH_TOKEN={creds.refresh_token}")


if __name__ == "__main__":
    main()
