import { Stack, StackProps } from "aws-cdk-lib";
import { Construct } from "constructs";
import * as ec2 from "aws-cdk-lib/aws-ec2";

/**
 * VPC + security groups. Nothing here is stateful (no RDS/EFS data), so this
 * stack is cheap to tear down and recreate independently of Storage/Compute.
 *
 * AZs are hardcoded (not looked up) so `cdk synth` never needs real AWS
 * credentials — Vpc's default AZ discovery does a live
 * DescribeAvailabilityZones context lookup, which fails offline. Two AZs is
 * the minimum for RDS/ALB/EFS to be Multi-AZ-capable later; this stack itself
 * only provisions single-AZ resources (see StorageStack) to keep the "someday"
 * variant cheap — bump to Multi-AZ before any real deploy.
 */
export interface NetworkStackProps extends StackProps {
  readonly azs: string[];
}

export class NetworkStack extends Stack {
  public readonly vpc: ec2.Vpc;

  /** ALB's own SG — public 80 ingress only, unrestricted egress (CDK default). */
  public readonly albSecurityGroup: ec2.SecurityGroup;

  /**
   * One shared SG for every app/data resource (Fargate tasks, RDS, EFS mount
   * targets), self-referencing all-traffic ingress. This mirrors docker-compose's
   * flat `expense-net` bridge network, where every service already reaches every
   * other service and there's no per-pair ACL today — so this doesn't reduce the
   * trust boundary local/vps already runs with. A real prod hardening pass would
   * split this into per-service SGs with narrow port-scoped rules; deliberately
   * not done here, see infra/README.md.
   */
  public readonly internalSecurityGroup: ec2.SecurityGroup;

  constructor(scope: Construct, id: string, props: NetworkStackProps) {
    super(scope, id, props);

    this.vpc = new ec2.Vpc(this, "Vpc", {
      ipAddresses: ec2.IpAddresses.cidr("10.42.0.0/16"),
      availabilityZones: props.azs,
      natGateways: 1, // single NAT — cheapest option; a real prod deploy would want one per AZ
      subnetConfiguration: [
        {
          name: "public",
          subnetType: ec2.SubnetType.PUBLIC,
          cidrMask: 24,
        },
        {
          // Fargate tasks, RDS, and EFS mount targets all live here. Using one
          // egress-capable tier (instead of splitting RDS/EFS into a separate
          // ISOLATED tier) keeps this skeleton to a single NAT gateway and one
          // route table per AZ — a real prod deploy would isolate the data tier.
          name: "private",
          subnetType: ec2.SubnetType.PRIVATE_WITH_EGRESS,
          cidrMask: 24,
        },
      ],
    });

    this.albSecurityGroup = new ec2.SecurityGroup(this, "AlbSg", {
      vpc: this.vpc,
      description: "ALB — public HTTP ingress",
      allowAllOutbound: true,
    });
    this.albSecurityGroup.addIngressRule(
      ec2.Peer.anyIpv4(),
      ec2.Port.tcp(80),
      "public HTTP — see infra/README.md for the TLS/ACM TODO",
    );

    this.internalSecurityGroup = new ec2.SecurityGroup(this, "InternalSg", {
      vpc: this.vpc,
      description: "Fargate services + RDS + EFS mount targets — flat trust zone (see class doc)",
      allowAllOutbound: true,
    });
    this.internalSecurityGroup.addIngressRule(
      this.internalSecurityGroup,
      ec2.Port.allTraffic(),
      "members reach each other freely, mirrors docker-compose's expense-net",
    );
    // Only the two paths the ALB actually forwards to (gateway, dashboard —
    // see ComputeStack's listener rules) need to accept traffic from outside
    // the internal SG.
    this.internalSecurityGroup.addIngressRule(
      this.albSecurityGroup,
      ec2.Port.tcp(8080),
      "ALB -> gateway",
    );
    this.internalSecurityGroup.addIngressRule(
      this.albSecurityGroup,
      ec2.Port.tcp(4200),
      "ALB -> dashboard",
    );
  }
}
