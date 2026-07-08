import { Stack, StackProps } from "aws-cdk-lib";
import { Construct } from "constructs";
import * as iam from "aws-cdk-lib/aws-iam";

export interface AiStackProps extends StackProps {
  readonly region: string;
}

/**
 * The AI "infra" on this stack is entirely IAM — Bedrock itself is serverless,
 * there's nothing to provision. This stack exists as its own fragment (per the
 * storage/ai/compute split) so the Bedrock access boundary is reviewable on its
 * own, independent of ECS task wiring.
 *
 * Scoped to exactly the model IDs facade's application-aws.yml and agents'
 * graph/llm.py reference today (Claude 3.5 Sonnet/Haiku, Titan Embed Text v1) —
 * not "bedrock:InvokeModel on *" — so a compromised task role can't invoke
 * arbitrary (potentially expensive) foundation models. Foundation-model ARNs
 * are region-scoped with no account ID (confirmed via AWS's Bedrock IAM docs):
 * arn:aws:bedrock:<region>::foundation-model/<model-id>.
 */
export class AiStack extends Stack {
  public readonly bedrockInvokePolicy: iam.ManagedPolicy;

  constructor(scope: Construct, id: string, props: AiStackProps) {
    super(scope, id, props);

    const modelIds = [
      // facade: application-aws.yml BEDROCK_CHAT_MODEL_ID default + embedding
      "anthropic.claude-3-5-sonnet-20241022-v2:0",
      "amazon.titan-embed-text-v1",
      // agents: graph/llm.py BEDROCK_CLASSIFY_MODEL_ID / BEDROCK_EXTRACT_MODEL_ID defaults
      "anthropic.claude-3-5-haiku-20241022-v1:0",
    ];

    this.bedrockInvokePolicy = new iam.ManagedPolicy(this, "BedrockInvokePolicy", {
      description: "Invoke the specific Bedrock foundation models this app uses",
      statements: [
        new iam.PolicyStatement({
          sid: "InvokeExpenseAgentModels",
          effect: iam.Effect.ALLOW,
          actions: ["bedrock:InvokeModel", "bedrock:InvokeModelWithResponseStream"],
          resources: modelIds.map(
            (id) => `arn:aws:bedrock:${props.region}::foundation-model/${id}`,
          ),
        }),
      ],
    });
  }
}
