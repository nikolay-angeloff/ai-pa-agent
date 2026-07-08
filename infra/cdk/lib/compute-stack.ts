import { Duration, Stack, StackProps } from "aws-cdk-lib";
import { Construct } from "constructs";
import * as ec2 from "aws-cdk-lib/aws-ec2";
import * as ecs from "aws-cdk-lib/aws-ecs";
import * as ecr from "aws-cdk-lib/aws-ecr";
import * as efs from "aws-cdk-lib/aws-efs";
import * as elbv2 from "aws-cdk-lib/aws-elasticloadbalancingv2";
import * as iam from "aws-cdk-lib/aws-iam";
import * as logs from "aws-cdk-lib/aws-logs";
import * as rds from "aws-cdk-lib/aws-rds";
import * as secretsmanager from "aws-cdk-lib/aws-secretsmanager";

export interface ComputeStackProps extends StackProps {
  readonly vpc: ec2.Vpc;
  readonly internalSecurityGroup: ec2.SecurityGroup;
  readonly albSecurityGroup: ec2.SecurityGroup;
  readonly repositories: Record<"gateway" | "facade" | "agents" | "webhook" | "dashboard", ecr.Repository>;
  readonly database: rds.DatabaseInstance;
  readonly fileSystem: efs.FileSystem;
  readonly rawStorageAccessPoint: efs.AccessPoint;
  readonly qdrantAccessPoint: efs.AccessPoint;
  readonly bedrockInvokePolicy: iam.IManagedPolicy;
  readonly jwtSecret: secretsmanager.Secret;
  readonly eventsSecret: secretsmanager.Secret;
  readonly gmailSecret: secretsmanager.Secret;
  readonly telegramSecret: secretsmanager.Secret;
  readonly miscSecret: secretsmanager.Secret;
  /** Image tag to deploy — passed via CDK context (-c imageTag=...), defaults to "latest". */
  readonly imageTag: string;
}

// Cloud Map private DNS namespace for Service Connect. Every service is reachable
// at `<name>.<NAMESPACE>` from any other service's task — the AWS equivalent of
// docker-compose's bare `http://facade:8081` short names, except the namespace
// suffix is required here: Cloud Map namespaces aren't in the VPC resolver's
// default search path the way docker-compose's bridge network DNS is. Every env
// var below that points at another service spells out the full `dns()` form —
// this is the one systematic difference from docker-compose's URLs, config-only,
// no application code changes needed on either side.
const NAMESPACE = "expense.internal";
const dns = (service: string) => `${service}.${NAMESPACE}`;

interface ServiceSpec {
  name: string;
  cpu: number;
  memoryLimitMiB: number;
  containerPort: number;
  image: ecs.ContainerImage;
  environment?: Record<string, string>;
  secrets?: Record<string, ecs.Secret>;
  taskRolePolicies?: iam.IManagedPolicy[];
  efsMount?: { accessPoint: efs.AccessPoint; containerPath: string; readOnly: boolean };
}

export class ComputeStack extends Stack {
  public readonly cluster: ecs.Cluster;
  public readonly loadBalancer: elbv2.ApplicationLoadBalancer;
  private internalSecurityGroup!: ec2.ISecurityGroup;

  constructor(scope: Construct, id: string, props: ComputeStackProps) {
    super(scope, id, props);

    this.internalSecurityGroup = props.internalSecurityGroup;

    this.cluster = new ecs.Cluster(this, "Cluster", {
      vpc: props.vpc,
      clusterName: "expense-agent",
      containerInsightsV2: ecs.ContainerInsights.DISABLED, // opt-in paid feature, off for this "someday" skeleton
    });
    this.cluster.addDefaultCloudMapNamespace({ name: NAMESPACE, useForServiceConnect: true });

    this.loadBalancer = new elbv2.ApplicationLoadBalancer(this, "Alb", {
      vpc: props.vpc,
      internetFacing: true,
      securityGroup: props.albSecurityGroup,
      vpcSubnets: { subnetType: ec2.SubnetType.PUBLIC },
    });
    // HTTP-only: no ACM certificate/domain in scope for a not-yet-deployed skeleton.
    // A real deploy would add an HTTPS listener (ACM cert + Route53 record) and
    // redirect 80 -> 443 — see infra/README.md.
    const listener = this.loadBalancer.addListener("HttpListener", {
      port: 80,
      protocol: elbv2.ApplicationProtocol.HTTP,
      open: true,
    });

    const dbSecret = props.database.secret!;
    const dbHost = props.database.dbInstanceEndpointAddress;
    const dbPort = props.database.dbInstanceEndpointPort;

    // ── facade ───────────────────────────────────────────────────────────────
    this.addService({
      name: "facade",
      cpu: 512,
      memoryLimitMiB: 1024,
      containerPort: 8081,
      image: ecs.ContainerImage.fromEcrRepository(props.repositories.facade, props.imageTag),
      taskRolePolicies: [props.bedrockInvokePolicy],
      environment: {
        SPRING_PROFILES_ACTIVE: "aws",
        SPRING_DATASOURCE_URL: `jdbc:postgresql://${dbHost}:${dbPort}/expense`,
        QDRANT_HOST: dns("qdrant"),
        QDRANT_PORT: "6334",
        AGENT_SVC_URL: `http://${dns("agents")}:8000`,
        WEBHOOK_URL: `http://${dns("webhook")}:3000`,
        INSIGHT_CRON: "0 0 8 * * *",
        BEDROCK_REGION: this.region,
        RAW_STORAGE_PATH: "/data/raw",
      },
      secrets: {
        SPRING_DATASOURCE_USERNAME: ecs.Secret.fromSecretsManager(dbSecret, "username"),
        SPRING_DATASOURCE_PASSWORD: ecs.Secret.fromSecretsManager(dbSecret, "password"),
        EVENTS_SECRET: ecs.Secret.fromSecretsManager(props.eventsSecret),
        GMAIL_CLIENT_ID: ecs.Secret.fromSecretsManager(props.gmailSecret, "clientId"),
        GMAIL_CLIENT_SECRET: ecs.Secret.fromSecretsManager(props.gmailSecret, "clientSecret"),
        GMAIL_REFRESH_TOKEN: ecs.Secret.fromSecretsManager(props.gmailSecret, "refreshToken"),
      },
      efsMount: { accessPoint: props.rawStorageAccessPoint, containerPath: "/data/raw", readOnly: false },
    });

    // ── agents ───────────────────────────────────────────────────────────────
    this.addService({
      name: "agents",
      cpu: 256,
      memoryLimitMiB: 512,
      containerPort: 8000,
      image: ecs.ContainerImage.fromEcrRepository(props.repositories.agents, props.imageTag),
      taskRolePolicies: [props.bedrockInvokePolicy],
      environment: {
        FACADE_URL: `http://${dns("facade")}:8081`,
        POSTGRES_HOST: dbHost,
        POSTGRES_PORT: dbPort,
        POSTGRES_DB: "expense",
        LLM_PROVIDER: "bedrock",
        BEDROCK_REGION: this.region,
        LANGSMITH_PROJECT: "expense-agent",
        LANGSMITH_TRACING: "false",
        LANGCHAIN_PROJECT: "expense-agent",
        LANGCHAIN_TRACING_V2: "false",
      },
      secrets: {
        POSTGRES_USER: ecs.Secret.fromSecretsManager(dbSecret, "username"),
        POSTGRES_PASSWORD: ecs.Secret.fromSecretsManager(dbSecret, "password"),
        LANGSMITH_API_KEY: ecs.Secret.fromSecretsManager(props.miscSecret, "langsmithApiKey"),
        LANGCHAIN_API_KEY: ecs.Secret.fromSecretsManager(props.miscSecret, "langsmithApiKey"),
      },
      efsMount: { accessPoint: props.rawStorageAccessPoint, containerPath: "/data/raw", readOnly: true },
    });

    // ── gateway ──────────────────────────────────────────────────────────────
    // SPRING_PROFILES_ACTIVE stays "local" on purpose: gateway has no AWS-specific
    // config (no LLM/vector-store swap), just env-driven routing URIs — nothing
    // to override in an aws profile, so none was created (Step 1's "only touch
    // facade" boundary applies here too).
    const gatewayService = this.addService({
      name: "gateway",
      cpu: 256,
      memoryLimitMiB: 512,
      containerPort: 8080,
      image: ecs.ContainerImage.fromEcrRepository(props.repositories.gateway, props.imageTag),
      environment: {
        SPRING_PROFILES_ACTIVE: "local",
        FACADE_URI: `http://${dns("facade")}:8081`,
        WEBHOOK_URI: `http://${dns("webhook")}:3000`,
        WEBHOOK_WS_URI: `ws://${dns("webhook")}:3000`,
      },
      secrets: {
        JWT_SECRET: ecs.Secret.fromSecretsManager(props.jwtSecret),
      },
    });

    // ── webhook ──────────────────────────────────────────────────────────────
    this.addService({
      name: "webhook",
      cpu: 256,
      memoryLimitMiB: 512,
      containerPort: 3000,
      image: ecs.ContainerImage.fromEcrRepository(props.repositories.webhook, props.imageTag),
      environment: {
        GATEWAY_URL: `http://${dns("gateway")}:8080`,
        PORT: "3000",
      },
      secrets: {
        TELEGRAM_BOT_TOKEN: ecs.Secret.fromSecretsManager(props.telegramSecret, "botToken"),
        TELEGRAM_WEBHOOK_SECRET: ecs.Secret.fromSecretsManager(props.telegramSecret, "webhookSecret"),
        TELEGRAM_CHAT_ID: ecs.Secret.fromSecretsManager(props.telegramSecret, "chatId"),
        EVENTS_SECRET: ecs.Secret.fromSecretsManager(props.eventsSecret),
      },
    });

    // ── dashboard ────────────────────────────────────────────────────────────
    // No DOMAIN/ACM in scope (see the HTTP-only listener note above), so the
    // dashboard's browser-facing API/WS origin points straight at the ALB's own
    // DNS name instead of a custom domain — deployable as-is, no domain purchase
    // required to get a working "someday" stack up.
    const dashboardService = this.addService({
      name: "dashboard",
      cpu: 256,
      memoryLimitMiB: 512,
      containerPort: 4200,
      image: ecs.ContainerImage.fromEcrRepository(props.repositories.dashboard, props.imageTag),
      environment: {
        API_BASE_URL: `http://${this.loadBalancer.loadBalancerDnsName}`,
        WS_BASE_URL: `ws://${this.loadBalancer.loadBalancerDnsName}`,
      },
    });

    // ── qdrant ───────────────────────────────────────────────────────────────
    // Not one of this repo's built images — pulled straight from Docker Hub,
    // same as docker-compose's `image: qdrant/qdrant:latest` (no ECR repo for it
    // in StorageStack).
    this.addService({
      name: "qdrant",
      cpu: 256,
      memoryLimitMiB: 512,
      containerPort: 6334,
      image: ecs.ContainerImage.fromRegistry("qdrant/qdrant:latest"),
      efsMount: { accessPoint: props.qdrantAccessPoint, containerPath: "/qdrant/storage", readOnly: false },
    });

    // ── ALB routing — mirrors the root Caddyfile's rules exactly ───────────────
    // /api/*, /webhook/telegram, /ws -> gateway; everything else (default,
    // no conditions/priority) -> dashboard.
    listener.addTargets("GatewayTarget", {
      priority: 10,
      conditions: [elbv2.ListenerCondition.pathPatterns(["/api/*", "/webhook/telegram", "/ws"])],
      port: 8080,
      protocol: elbv2.ApplicationProtocol.HTTP,
      targets: [gatewayService],
      healthCheck: { path: "/actuator/health", interval: Duration.seconds(30) },
    });
    listener.addTargets("DashboardTarget", {
      port: 4200,
      protocol: elbv2.ApplicationProtocol.HTTP,
      targets: [dashboardService],
      healthCheck: { path: "/", interval: Duration.seconds(30) },
    });

  }

  private addService(spec: ServiceSpec): ecs.FargateService {
    const taskDefinition = new ecs.FargateTaskDefinition(this, `${spec.name}-task`, {
      cpu: spec.cpu,
      memoryLimitMiB: spec.memoryLimitMiB,
    });

    if (spec.taskRolePolicies) {
      for (const policy of spec.taskRolePolicies) {
        taskDefinition.taskRole.addManagedPolicy(policy);
      }
    }

    const container = taskDefinition.addContainer(`${spec.name}-container`, {
      image: spec.image,
      environment: spec.environment,
      secrets: spec.secrets,
      logging: ecs.LogDrivers.awsLogs({
        streamPrefix: spec.name,
        logGroup: new logs.LogGroup(this, `${spec.name}-logs`, {
          logGroupName: `/expense-agent/${spec.name}`,
          retention: logs.RetentionDays.ONE_WEEK,
        }),
      }),
    });

    container.addPortMappings({
      name: spec.name,
      containerPort: spec.containerPort,
      protocol: ecs.Protocol.TCP,
    });

    if (spec.efsMount) {
      const volumeName = `${spec.name}-efs`;
      taskDefinition.addVolume({
        name: volumeName,
        efsVolumeConfiguration: {
          fileSystemId: spec.efsMount.accessPoint.fileSystem.fileSystemId,
          transitEncryption: "ENABLED",
          authorizationConfig: { accessPointId: spec.efsMount.accessPoint.accessPointId },
        },
      });
      container.addMountPoints({
        sourceVolume: volumeName,
        containerPath: spec.efsMount.containerPath,
        readOnly: spec.efsMount.readOnly,
      });
    }

    const service = new ecs.FargateService(this, `${spec.name}-service`, {
      cluster: this.cluster,
      taskDefinition,
      desiredCount: 1,
      minHealthyPercent: 100,
      maxHealthyPercent: 200,
      assignPublicIp: false,
      vpcSubnets: { subnetType: ec2.SubnetType.PRIVATE_WITH_EGRESS },
      securityGroups: [this.internalSecurityGroup],
      // Fail fast instead of the default up-to-3-hour hang if a task can't
      // reach a stable state (e.g. bad image, crash-looping on startup).
      circuitBreaker: { rollback: true },
      // ALB-fronted services (gateway, dashboard) need a grace period so the
      // target group doesn't mark the task unhealthy while Spring Boot/Angular
      // is still starting up; harmless for the rest, so applied uniformly.
      healthCheckGracePeriod: Duration.seconds(60),
      serviceConnectConfiguration: {
        services: [
          {
            portMappingName: spec.name,
            dnsName: spec.name,
            port: spec.containerPort,
          },
        ],
      },
    });

    return service;
  }
}
