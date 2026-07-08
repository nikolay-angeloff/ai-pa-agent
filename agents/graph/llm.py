"""
build_chat_model — the one place that decides OpenAI vs Bedrock.

Python has no Spring-style profile files, so LLM_PROVIDER (env var, default
"openai") is the equivalent switch: unset or "openai" reproduces exactly what
classify_node/extract_node/query_node already did before this module existed —
local/vps behavior is untouched. "bedrock" is the Phase 5 aws-profile path,
selected by the ECS task definition (infra/cdk/), never by docker-compose.

Two tiers, matching CLAUDE.md's "cheap model for classify/routing, stronger
for extraction/query" — and matching what the three nodes already do today
(classify_node: cheap: gpt-5.4-nano / query_node's two calls and extract_node:
strong: gpt-5.5), just resolved per provider instead of hardcoded.
"""

import os

from langchain_core.language_models.chat_models import BaseChatModel
from langchain_openai import ChatOpenAI

PROVIDER = os.environ.get("LLM_PROVIDER", "openai").lower()

# Same env var name facade uses for its own Bedrock region (application-aws.yml)
# — one name across both services instead of two, since it's the same AWS account/region.
_BEDROCK_REGION = os.environ.get("BEDROCK_REGION", "eu-central-1")

_OPENAI_MODELS = {"cheap": "gpt-5.4-nano", "strong": "gpt-5.5"}

# Bedrock model IDs are env-driven (not hardcoded) so they can be swapped without a
# code change once actual Bedrock model access is granted per AWS account/region.
# Defaults: Claude 3.5 Haiku for the cheap/routing tier, Claude 3.5 Sonnet for the
# strong tier — same Sonnet id facade's application-aws.yml uses, so extraction
# quality/prompt behavior stays consistent between the two services on AWS.
_BEDROCK_MODEL_ENV = {
    "cheap": "BEDROCK_CLASSIFY_MODEL_ID",
    "strong": "BEDROCK_EXTRACT_MODEL_ID",
}
_BEDROCK_MODEL_DEFAULTS = {
    "cheap": "anthropic.claude-3-5-haiku-20241022-v1:0",
    "strong": "anthropic.claude-3-5-sonnet-20241022-v2:0",
}


def build_chat_model(tier: str, *, max_tokens: int, temperature: float = 0) -> BaseChatModel:
    """tier is "cheap" or "strong" — see module docstring."""
    if PROVIDER == "bedrock":
        from langchain_aws import ChatBedrockConverse  # imported lazily: openai-only

        # local/vps never installs langchain-aws (it's only added for the aws
        # profile — see requirements.txt), so this import must stay inside the
        # bedrock branch, not at module top level.
        model_id = os.environ.get(_BEDROCK_MODEL_ENV[tier], _BEDROCK_MODEL_DEFAULTS[tier])
        return ChatBedrockConverse(
            model=model_id,
            region_name=_BEDROCK_REGION,
            temperature=temperature,
            max_tokens=max_tokens,
            # No explicit credentials: falls through to boto3's default credential
            # chain, which on ECS Fargate resolves to the task's IAM role — same
            # zero-static-AWS-credentials approach as facade's application-aws.yml.
        )

    # default / "openai" — byte-for-byte the same construction as before this
    # module existed. reasoning_effort="none": gpt-5.x models can otherwise burn
    # the whole max_tokens budget on hidden reasoning and return empty content,
    # which then fails json.loads() at every call site — not a Bedrock concern,
    # Claude on Bedrock has no equivalent hidden-reasoning-by-default behavior.
    return ChatOpenAI(
        model=_OPENAI_MODELS[tier],
        max_tokens=max_tokens,
        temperature=temperature,
        reasoning_effort="none",
    )


def response_text(response) -> str:
    """Normalize response.content to a plain string across providers.

    ChatOpenAI always returns a str. ChatBedrockConverse can return a list of
    content blocks instead (e.g. a "reasoning_content" block alongside "text"
    when a reasoning-capable model is used) — verified against langchain-aws's
    own samples. json.loads() at every call site needs a str either way, so
    this concatenates the text blocks when content isn't already one.
    """
    content = response.content
    if isinstance(content, str):
        return content
    return "".join(
        block.get("text", "")
        for block in content
        if isinstance(block, dict) and block.get("type") == "text"
    )
