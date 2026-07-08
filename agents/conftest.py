import os
import sys

sys.path.insert(0, os.path.dirname(__file__))

# classify/extract/query instantiate ChatOpenAI(...) at import time; a dummy
# key lets the module import (and get monkeypatched in tests) without a real
# OpenAI account. No test in this suite makes a real API call.
os.environ.setdefault("OPENAI_API_KEY", "sk-test-dummy")
