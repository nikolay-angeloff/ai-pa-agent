import { RemovalPolicy, Stack, StackProps } from "aws-cdk-lib";
import { Construct } from "constructs";
import * as ec2 from "aws-cdk-lib/aws-ec2";
import * as rds from "aws-cdk-lib/aws-rds";
import * as efs from "aws-cdk-lib/aws-efs";
import * as ecr from "aws-cdk-lib/aws-ecr";

export interface StorageStackProps extends StackProps {
  readonly vpc: ec2.Vpc;
  readonly internalSecurityGroup: ec2.SecurityGroup;
}

const SERVICE_NAMES = ["gateway", "facade", "agents", "webhook", "dashboard"] as const;

/**
 * RDS Postgres, EFS (Fargate's persistent-volume mechanism — see below), and
 * ECR repos for the 5 built service images.
 *
 * RemovalPolicy.DESTROY everywhere: this stack is an inactive "someday" variant,
 * never holding real data, so `cdk destroy` should leave nothing orphaned behind
 * (and nothing billing) when torn down after a test synth/deploy. A real prod
 * cutover must flip RDS/EFS to RETAIN (+ enable RDS automated backups) before
 * it ever holds real expense data — see infra/README.md.
 */
export class StorageStack extends Stack {
  public readonly database: rds.DatabaseInstance;
  public readonly fileSystem: efs.FileSystem;
  public readonly rawStorageAccessPoint: efs.AccessPoint;
  public readonly qdrantAccessPoint: efs.AccessPoint;
  public readonly repositories: Record<(typeof SERVICE_NAMES)[number], ecr.Repository>;

  constructor(scope: Construct, id: string, props: StorageStackProps) {
    super(scope, id, props);

    // ── RDS Postgres ──────────────────────────────────────────────────────────
    // Single instance, no read replica, db.t4g.micro — sized for a portfolio
    // project's actual traffic, not for production load. Credentials are
    // generated + stored in Secrets Manager (never in code/CDK) and RDS
    // auto-populates host/port/dbname/engine into the same secret's JSON once
    // provisioned, which is what ComputeStack reads for facade/agents.
    this.database = new rds.DatabaseInstance(this, "Postgres", {
      engine: rds.DatabaseInstanceEngine.postgres({ version: rds.PostgresEngineVersion.VER_16_3 }),
      instanceType: ec2.InstanceType.of(ec2.InstanceClass.BURSTABLE4_GRAVITON, ec2.InstanceSize.MICRO),
      vpc: props.vpc,
      vpcSubnets: { subnetType: ec2.SubnetType.PRIVATE_WITH_EGRESS },
      securityGroups: [props.internalSecurityGroup],
      credentials: rds.Credentials.fromGeneratedSecret("postgres"),
      databaseName: "expense",
      allocatedStorage: 20,
      maxAllocatedStorage: 100, // storage autoscaling — cheap headroom, avoids a manual resize later
      multiAz: false,
      removalPolicy: RemovalPolicy.DESTROY,
      deleteAutomatedBackups: true,
    });

    // ── EFS — Fargate's persistent-volume mechanism ─────────────────────────────
    // CLAUDE.md's original plan (see application-aws.yml's TODO) was Qdrant on
    // EBS. Fargate *can* attach EBS since re:Invent 2023 via ecs.ServiceManagedVolume,
    // but that path is single-task-attach and newer/less battle-tested in CDK L2;
    // it also can't be shared read-write/read-only across two different services
    // the way facade/agents share raw attachments today. EFS does both jobs
    // (Qdrant's single-writer volume AND facade/agents' shared raw-storage volume)
    // through one well-documented mechanism, so it's used for both here instead
    // of mixing two different volume types for one skeleton stack.
    this.fileSystem = new efs.FileSystem(this, "SharedFs", {
      vpc: props.vpc,
      vpcSubnets: { subnetType: ec2.SubnetType.PRIVATE_WITH_EGRESS },
      securityGroup: props.internalSecurityGroup,
      encrypted: true,
      lifecyclePolicy: efs.LifecyclePolicy.AFTER_30_DAYS, // idle files -> Infrequent Access storage class, cheaper
      removalPolicy: RemovalPolicy.DESTROY,
    });

    // uid/gid 1000 is a generic non-root placeholder, not verified against each
    // container image's actual runtime user — fine for cdk synth / a first real
    // deploy smoke test, but revisit per-image (`docker run --rm <image> id`)
    // before trusting file permissions in a real deployment.
    this.rawStorageAccessPoint = this.fileSystem.addAccessPoint("RawStorageAp", {
      path: "/raw",
      createAcl: { ownerUid: "1000", ownerGid: "1000", permissions: "750" },
      posixUser: { uid: "1000", gid: "1000" },
    });
    this.qdrantAccessPoint = this.fileSystem.addAccessPoint("QdrantDataAp", {
      path: "/qdrant",
      createAcl: { ownerUid: "1000", ownerGid: "1000", permissions: "750" },
      posixUser: { uid: "1000", gid: "1000" },
    });

    // ── ECR ──────────────────────────────────────────────────────────────────
    this.repositories = Object.fromEntries(
      SERVICE_NAMES.map((name) => [
        name,
        new ecr.Repository(this, `Repo${name}`, {
          repositoryName: `expense-agent/${name}`,
          imageScanOnPush: true,
          removalPolicy: RemovalPolicy.DESTROY,
          emptyOnDelete: true,
          lifecycleRules: [{ description: "keep last 10 images", maxImageCount: 10 }],
        }),
      ]),
    ) as Record<(typeof SERVICE_NAMES)[number], ecr.Repository>;
  }
}
