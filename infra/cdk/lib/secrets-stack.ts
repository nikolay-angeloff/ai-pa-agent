import { RemovalPolicy, Stack, StackProps } from "aws-cdk-lib";
import { Construct } from "constructs";
import * as secretsmanager from "aws-cdk-lib/aws-secretsmanager";

/**
 * Secrets that don't come from an AWS-managed source (RDS auto-generates and
 * owns its own secret — see StorageStack — so it isn't duplicated here).
 *
 * Two kinds:
 *  - jwtSecret / eventsSecret: pure random shared secrets, CDK-generated at
 *    deploy time — nothing to fill in manually, same as .env's JWT_SECRET/
 *    EVENTS_SECRET today.
 *  - everything else: created EMPTY (placeholder JSON), because these values
 *    come from external OAuth/bot-registration flows (Google Cloud Console,
 *    @BotFather, smith.langchain.com) that happen outside CDK — exactly
 *    mirroring .env.example, where the same fields are blank until filled by
 *    hand. Fill them via `aws secretsmanager put-secret-value` post-deploy;
 *    see infra/README.md.
 */
export class SecretsStack extends Stack {
  public readonly jwtSecret: secretsmanager.Secret;
  public readonly eventsSecret: secretsmanager.Secret;
  public readonly gmailSecret: secretsmanager.Secret;
  public readonly telegramSecret: secretsmanager.Secret;
  public readonly miscSecret: secretsmanager.Secret;

  constructor(scope: Construct, id: string, props?: StackProps) {
    super(scope, id, props);

    this.jwtSecret = new secretsmanager.Secret(this, "JwtSecret", {
      description: "Gateway JWT signing secret (application-aws profile)",
      generateSecretString: { excludePunctuation: true, passwordLength: 48 },
      removalPolicy: RemovalPolicy.DESTROY,
    });

    this.eventsSecret = new secretsmanager.Secret(this, "EventsSecret", {
      description: "Shared secret for facade -> webhook internal event calls",
      generateSecretString: { excludePunctuation: true, passwordLength: 32 },
      removalPolicy: RemovalPolicy.DESTROY,
    });

    this.gmailSecret = new secretsmanager.Secret(this, "GmailOAuth", {
      description: "Gmail OAuth client — fill in post-deploy, see .env.example's Gmail section",
      generateSecretString: {
        secretStringTemplate: JSON.stringify({ clientId: "", clientSecret: "", refreshToken: "" }),
        generateStringKey: "_unused", // Secret requires exactly one generated key; real values are filled by hand
      },
      removalPolicy: RemovalPolicy.DESTROY,
    });

    this.telegramSecret = new secretsmanager.Secret(this, "TelegramBot", {
      description: "Telegram bot — fill in post-deploy (via @BotFather), see .env.example's Cockpit section",
      generateSecretString: {
        secretStringTemplate: JSON.stringify({ botToken: "", webhookSecret: "", chatId: "" }),
        generateStringKey: "_unused",
      },
      removalPolicy: RemovalPolicy.DESTROY,
    });

    this.miscSecret = new secretsmanager.Secret(this, "MiscSecrets", {
      description: "LangSmith API key — fill in post-deploy (smith.langchain.com -> Settings -> API Keys)",
      generateSecretString: {
        secretStringTemplate: JSON.stringify({ langsmithApiKey: "" }),
        generateStringKey: "_unused",
      },
      removalPolicy: RemovalPolicy.DESTROY,
    });
  }
}
