#!/usr/bin/env node
import "source-map-support/register";
import { App } from "aws-cdk-lib";
import { NetworkStack } from "../lib/network-stack";
import { StorageStack } from "../lib/storage-stack";
import { SecretsStack } from "../lib/secrets-stack";
import { AiStack } from "../lib/ai-stack";
import { ComputeStack } from "../lib/compute-stack";

const app = new App();

// Region-only env (no account) keeps `cdk synth` portable across any AWS
// account/credentials — matches BEDROCK_REGION's default in application-aws.yml
// / graph/llm.py. A real deploy pins the account too (`cdk deploy` will ask, or
// pass -c account=...) — see infra/README.md.
const region = app.node.tryGetContext("region") ?? process.env.CDK_DEFAULT_REGION ?? "eu-central-1";
const env = { region };

// Hardcoded, not auto-discovered: ec2.Vpc's default AZ lookup performs a live
// `DescribeAvailabilityZones` context call requiring real AWS credentials —
// this keeps `cdk synth` working with zero AWS access configured, as required.
const azs = [`${region}a`, `${region}b`];

// Image tag to deploy — `cdk deploy -c imageTag=v1.2.3`. Defaults to "latest"
// so a first `cdk synth`/`cdk deploy` works without extra flags.
const imageTag = app.node.tryGetContext("imageTag") ?? "latest";

const network = new NetworkStack(app, "ExpenseAgentNetwork", { env, azs });

const storage = new StorageStack(app, "ExpenseAgentStorage", {
  env,
  vpc: network.vpc,
  internalSecurityGroup: network.internalSecurityGroup,
});

const secrets = new SecretsStack(app, "ExpenseAgentSecrets", { env });

const ai = new AiStack(app, "ExpenseAgentAi", { env, region });

new ComputeStack(app, "ExpenseAgentCompute", {
  env,
  vpc: network.vpc,
  internalSecurityGroup: network.internalSecurityGroup,
  albSecurityGroup: network.albSecurityGroup,
  repositories: storage.repositories,
  database: storage.database,
  fileSystem: storage.fileSystem,
  rawStorageAccessPoint: storage.rawStorageAccessPoint,
  qdrantAccessPoint: storage.qdrantAccessPoint,
  bedrockInvokePolicy: ai.bedrockInvokePolicy,
  jwtSecret: secrets.jwtSecret,
  eventsSecret: secrets.eventsSecret,
  gmailSecret: secrets.gmailSecret,
  telegramSecret: secrets.telegramSecret,
  miscSecret: secrets.miscSecret,
  imageTag,
});
