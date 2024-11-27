# AWS Deployment Example

This guide provides a comprehensive deployment strategy for **EC2** and **ECS** using **Docker** and AWS services.

## Deployment strategy using **EC2**

This strategy enables an architecture where instances that do not need internet exposure can be securely placed in a
private subnet, while user-facing instances can be deployed in a public subnet.

Alternatively, all instances can be located in a private subnet, with the application's functionality exposed through an
**Application Load Balancer (ALB)**. The ALB can be attached to the service that would typically be user-facing.

### 1) Install **Docker** and **Docker-compose**

The first step is to prepare an **EC2** instance for the task.

You will need to install **Docker** and **Docker Compose** (I recommend **Docker Compose**, although it is not mandatory
for this setup).

```shell
sudo yum update -y  
sudo yum install -y docker
sudo systemctl enable docker
sudo systemctl start docker
```

```shell
sudo curl -L https://github.com/docker/compose/releases/latest/download/docker-compose-$(uname -s)-$(uname -m) -o /usr/bin/docker-compose

sudo chmod +x /usr/bin/docker-compose
```

### 2) Create **docker_boot.service** and copy it to `/etc/system/systemd`

It is desirable for our application to start automatically when the **EC2** instance boots. To achieve this, we need to
create a **systemd** service.

This basic **systemd** service will execute `docker-compose up -d --remove-orphans` during the **EC2** instance's
startup process.

[docker_boot.service](./docker_boot.service)

```
[Unit]
Description=docker boot
After=docker.service

[Service]
Type=oneshot
RemainAfterExit=yes
WorkingDirectory=/home/ec2-user
ExecStart=/usr/bin/docker-compose -f /home/ec2-user/docker-compose.yaml up -d --remove-orphans

[Install]
WantedBy=multi-user.target
```

For the systemd service to be effective, it must be placed in the appropriate directory.

```shell
sudo mv /home/ec2-user/docker_boot.service /etc/systemd/system
```

### 3) Create **AMI** from step 1) and 2)

Repeating steps 1 and 2 manually for each new service deployment is not ideal.

To streamline the process, you should create an **AMI** from an instance where steps 1 and 2 have already been
completed.

### 4) Create user-data script

The behavior of the **AMI** will be customized using shell scripts provided as AWS user data files.

The script should perform the following tasks:

- Fetch the **Docker** image from **S3** (or **ECR**; however, **S3** is preferable for private subnets as it can be
- Fetch the **Docker** image from **S3** (or **ECR**; however, **S3** is preferable for private subnets as it can be
  accessed for free using a gateway endpoint).
- Load the image into **Docker**.
- Generate the `docker-compose.yaml` file.
- Fetch or create any additional files required by `docker-compose.yaml`.
- Enable and start the **docker_boot.service**.

example scripts:

- [redis user-data](./redis/user-data.txt)
- [discovery user-data](./discovery/user-data.txt)
- [messages user-data](./messages/user-data.txt)
- [gateway user-data](./gateway/user-data.txt)

### Enable and Start docker_boot.service

To enable the systemd service, run the following commands:

```shell
sudo systemctl enable docker_boot.service
sudo systemctl start docker_boot.service
```

### Notes

In the case of my application, the **messages** and **gateway** services need to communicate with the **Redis** and
**discovery** services.

I manually assigned private static **IPv4** addresses as follows:

```
redis - 10.0.0.4
discovery - 10.0.0.5
```

These **IPv4** addresses can be used in user data to help other services locate the **Redis** and **discovery**
services.

If hardcoding **IPv4** addresses is not desirable, you can create a **Route 53** private hosted zone with **A** records.
However, this approach also involves manual work, such as creating the **A** records.

A more dynamic approach would be to use the `aws ec2 discover-instances` command to fetch **IPv4** addresses based on
**EC2** instance tags or other attributes. However, this method requires access to AWS services, which means either
public internet access or a **VPC** interface endpoint (a paid service).

In my scripts, I use **IMDSv2** to fetch the IP address and hostname of the **EC2** instance being configured. This is
necessary for properly setting up the **Eureka** server.

### **EC2** templates

To avoid repetitive **EC2** instance configurations, create an **EC2** template for each service. When using an **Auto
Scaling Group (ASG)**, avoid specifying network details such as a static **IPv4** address. If a service, such as
**Redis**, requires a static **IPv4** address, it can be hardcoded into the configuration. However, this approach is
suitable
primarily for learning or testing purposes and is not ideal for scalable environments.

## **VPC**

In my deployment, I aim to create public subnets with internet access and private subnets that are as secure as
possible.

### 1) Create custom **VPC**

To enable DNS-based communication between **EC2** instances, you need to enable DNS hostnames for your **VPC**.
Make sure that DNS resolution is also enabled.

### 2) Create private subnet and public subnet

1) Create two **route tables**: one for the **public subnet** and one for the **private subnet**.

2) For internet access in the **public subnet**, create an **Internet Gateway (IGW)** and attach it to the **VPC**.

3) **Subnet IP Address Assignment**:

    - I assigned **10.0.0.0/17** for the **private subnet** and **10.0.128.0/17** for the **public subnet**, resulting
      in a 50-50 split.

4) Make the subnet **public**:

    - Create a route in the **public route table** with the destination **0.0.0.0/0** and point it to the **IGW**.

5) To ensure high availability, create three subnets across three **Availability Zones (AZs)**, each having a **public**
   and a **private subnet**.

#### **Instance Connect Endpoint**

To enable SSH access to instances in the **private subnet**, you need to create an
**EC2 Instance Connect Endpoint (ICE)**.

- You can create the **ICE** in a single subnet and then use it across other subnets within the same **VPC**, but doing
  so may impact high availability.
- Steps for creating **ICE**:
    - Assign the **ICE** to your chosen **VPC** and **private subnet**.
    - Create a **Security Group (SG)** for **ICE**, allowing outbound SSH traffic for the instances that
      require **ICE** access.
    - Add the **ICE** security group as an inbound rule in the target instance's security group.

#### **S3 Gateway Endpoint**

To use **S3** from within your private network, create an **S3 Gateway Endpoint**. This is one of the two free gateway
endpoints offered by AWS, the other being the **DynamoDB Gateway Endpoint**. Using these endpoints is more efficient
than accessing **S3** over the public internet because the traffic goes through the **AWS** private network instead.

Steps to create an **S3 Gateway Endpoint**:

- Create the **S3 Gateway Endpoint** (`com.amazonaws.<region>.s3`) and assign it to your chosen **VPC**.
- Add a route to the **private subnet's route table**, pointing to the **S3 Gateway Endpoint** as the target. The source
  should be the pre-created **AWS prefix list** for **S3** that is associated with the endpoint.
- In the **Security Group (SG)** of the **EC2** instances that need to access **S3**, create an outbound rule allowing
  **HTTPS** traffic to the same prefix list used in the route table.
- Create an **IAM Role** for **EC2** instances, granting them permission to read from **S3** (or additional permissions,
  if needed).

### Subnet per AZ for private/public subnets

If VPC is 10.0.0.0/16 32k

```
| Subnet Name                               | CIDR Block       | IPs Allocated |
|-------------------------------------------|------------------|---------------|
| aws-deployment-example-private-subnet-a   | 10.0.0.0/18      | 16k           |
| aws-deployment-example-private-subnet-b   | 10.0.64.0/19     | 8k            |
| aws-deployment-example-private-subnet-c   | 10.0.96.0/19     | 8k            |
| aws-deployment-example-public-subnet-a    | 10.0.128.0/18    | 16k           |
| aws-deployment-example-public-subnet-b    | 10.0.192.0/19    | 8k            |
| aws-deployment-example-public-subnet-c    | 10.0.224.0/19    | 8k            |
```

## **S3**

Technically, **ECR** would be a better choice than **S3** for storing Docker images. However, you cannot access **ECR**
from private subnets for free; an **interface endpoint** would be required. Interestingly, **ECR** uses **S3** as its
underlying storage.

To download an image from **S3**, treat it as any other file:

```shell
aws s3 cp s3://<bucket-name>/<file-name> <file-name>
```

To use S3 for this purpose, build the image locally and save it with:

```shell
 docker save -o gateway:latest.tar gateway:latest
```

in user-data scripts load the file into docker, do it with:

```shell
 docker load -i gateway:latest.tar
```

Remember to later remove the tar file

## Security Groups

Make the security group (SG) rules as strict as possible.

- Every SG should include an **inbound rule** for **ICE** (EC2 Instance Connect Endpoint) and an **outbound rule** for
  **S3**.
- For **Eureka** and **Redis**, use **custom TCP rules** with the appropriate ports.

### Eureka

For the **Eureka server**, enable a **custom TCP inbound rule** for the port on which the server is hosted.

- Services that depend on the Eureka server should include **outbound rules** for the same port to allow communication.

### Redis

The configuration for **Redis** is the same as for **Eureka**. Enable a **custom TCP inbound rule** for the port Redis
is running on, and services that need to communicate with Redis should allow outbound traffic for that port.

## Frontend

To deploy the frontend application, place it in an **S3 bucket** configured for **static file hosting**.

- **CORS** (Cross-Origin Resource Sharing) must be set up. To do so, configure the `.env` variable of the **gateway
  service** with the hostname of the S3 bucket.

## Secrets

Instead of providing secrets through a **user-data script**, a better approach is to use **AWS SSM** (AWS Systems
Manager) to securely fetch secrets using the **AWS CLI** from within the application code.

- The drawback of this solution is that, for private subnets, an **interface endpoint** is required (which is a paid
  service).

## Describe Instances

When using either a **public network** or **EC2 interface endpoint**, the AWS CLI command `ec2 describe-instances` can
be used to fetch the **IPv4 addresses** or **hostnames** of other services by their **tags**. This is a great method for
service discovery.

## **Route 53**

To not use hardcoded **IPv4** addresses for **Eureka** and **Redis**, you can create **A records** for these **IPv4**
addresses in **Route 53**.

- Enable **DNS resolution** and enable **DNS hostnames**.
- Create a **private hosted zone** for the chosen **VPC** (this is paid).
- Create **A records** for the chosen **IPv4** addresses. One can use a list of addresses and later use a **load
  balancing** strategy.

In my case, I created these records:

```
| Record name           | Type | Routing policy | Target      | TTL  |
|-----------------------|------|----------------|-------------|------|
| discovery.messages.com | A    | Simple         | 10.0.0.5    | 300  |
| redis.messages.com     | A    | Simple         | 10.0.0.4    | 300  |
```

Make sure that eureka and redis have these static private addresses.

Make sure that **Eureka** and **Redis** have these static private addresses.

## **Cloud Map**

**Cloud Map** can be used instead of **Route 53** to create **DNS** hostnames. In my use case, there is no difference.
In reality, **Cloud Map** uses **Route 53** underneath.

- Create a **namespace**. Specify whether only API Calls (**AWS EC2 discover instances**) are needed or also **DNS
  hostnames**.
- Create a **service** for the target instance. One can select options here for **DNS** and **health checks**.
- Register the instance by providing its **IPv4** address and port.

When using **DNS**, the steps semantically are the same as when using **Route 53**.

The **DNS** name for each service is:

```
service_name.namespace_name -> ipv4 for the service if A record used in service definition
```

## **Deployment strategy For ECS**

Using **ECS** requires much less labor than **EC2**, but it is more complicated because **ECS** abstractions must be
understood.

I don't want to pay for **network interfaces**, so I need to run my tasks in the **public network**.

The proper solution would be to create two clusters: one for instances that don't require access to the public
internet (**private network**) and one for instances that are **user-facing**. As was the case in **EC2** deployment,
all tasks can be run in the private network, and the **ALB** can be **user-facing**.

The service that we need and requires either **public internet** or an **interface endpoint** is **ECR**. Using **public
internet** has the nice benefit that it allows for fetching any image that is publicly available.

For the sake of **ECS** deployment, I deploy only the **messages service** and **Redis**.

### **Public Cluster**

I use a cluster with **EC2 ASG** and free **t3.micro** instances.

#### **Network**

Use the **public network** for the cluster.

When using **Docker Bridge**, the tasks don't have their own **SG**, they share them with the **ECS EC2 instances**.

There is no need to make certain services accessible from the internet, for example, **Redis**. In the shared **SG** (
the **ECS EC2** cluster one), allow inbound traffic for **Redis** only for **private IPv4** addresses from these
networks. In my case, it is **10.0.128.0/17**.

##### **Service Connect**

**Service Connect** is an abstraction that can be used in **ECS** to connect services together. It fulfills the role of
**service discovery**.

For **Redis** (client and server mode):

- Enable **client** and **server mode**.
- Choose **namespace** for the **Service Connect**, this can be the one that is automatically created when the cluster
  is created.
- Configure the mapping.

Mapping configuration has several fields:

- **Port Alias**: The port that was defined in the task definition. It represents the connection to be exposed via *
  *Service Connect**.
- **Discovery**: An optional field, it will be used as a service name in **Cloud Map** (**Service discovery** is again a
  wrapper for other services).
- **DNS**: The most important field, this will be the **hostname** of this service in **Service Connect**, and other
  services will use this to connect to this service.
- **Port**: The port that is meant for this service. I chose the same port as in the task definition.

For **clients** (**client mode**):

- Enable **client mode**.
- Choose **namespace** (the same as in **Redis** service configuration).
- In application code, use the same **DNS name** as in the **Redis** configuration.

When using **ECS Service Connect** with tasks that use **bridge networking mode**, the **SG** for **EC2 instances** in
the **ECS cluster** must allow inbound **TCP** traffic from the upper dynamic port range, which is **32768 - 65535**.
Otherwise, **Service Connect** will not work.
See [link](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/service-connect-concepts-deploy.html#service-connect-concepts-network)

I am not sure why this range is valid, but experimentally I determined that it is.

You need to reserve resources for proxy at least 256 CPU and 64MiB. If you expect more than 500 requests per second use
512 CPU. If you expect 100 Service Connect services or 2000 tasks use 128 MiB. Using fargate has its own requirements.
See: [link](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/service-connect-concepts-deploy.html#service-connect-concepts-proxy)

##### ECS-public-cluster-EC2-SG

```
| Security Group ID                        | Protocol | Port Range           | CIDR Block      | Description                    | Notes                         |
|------------------------------------------|----------|----------------------|-----------------|--------------------------------|-------------------------------|
| sgr-05be99a4f5e5cff55                    | IPv4     | Custom TCP (TCP)     | 6379            | Private IPs in public subnet   |                               |
| sgr-078e57c38aaca6c54                    | IPv4     | HTTP (TCP)           | 80              | Access from any client (0.0.0.0/0) |                               |
| sgr-00427ed8e81f6d727                    | IPv4     | Custom TCP (TCP)     | 32768 - 65535   | Dynamic ports for service connect |                               |
| sgr-0a347469160b4cee1                    | IPv4     | SSH (TCP)            | 22              | SSH access from my PC (specific IP) | `<your public IP>`          |
```

### **Task Definitions**

**Task definitions** are blueprints for the deployment of containers. They are similar to the contents of a
**docker-compose** file.

Example task definition:

- [**redis task definition json**](./redis/redis-task-definition.json)
- [**messages task definition json**](./messages/messages-task-definition.json)

#### **Image**

My **Redis** requires a custom **redis.conf** and **user.acl** file for authentication. It is hard to mount these files
into the container on startup of the task. The idiomatic approach is to build them into the image by using a custom
**Dockerfile**.

When a change of password or some configuration is required, create a new image and upload it to **ECR**.

#### **Resources**

At most, I could specify **2 vCPU** and **0.905 GB** for **t3.micro** (free-tier). The rest of the **1GB** is probably
needed by the **ECS agent container** that runs on each **ECS** instance.

Remember to reserve some capacity for Service Connect proxy. 2 vCPU - 256 CPU = 1.75 vCPU and 0.905 * 1024 - 0.64 MiB =
862.72 MiB = 0.8425 GB I will pick lower bound so 0.842 GB

See: [link](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/service-connect-concepts-deploy.html#service-connect-concepts-proxy)

#### **Network**

To make the task discoverable from the public network, use the **host** type of **bridge**. **awsvpc** does not work
because it does not give public IPs to the tasks, and one would need to use a **NAT gateway**, which costs money.

**Docker bridge** requires specifying ports on both the host **EC2 instance** and the **Docker container**—typical
configuration for **Docker**.

In theory, the **network type Host** could also be used, but in practice, we would get the same deployment type as in
**EC2** deployment.

#### **Storage**

To use storage to persist data, one can use a **Docker volume** with the **driver** **local** (you must use lowercase,
otherwise, your task will not boot without logs) and **Scope shared** (so that when you restart the tasks, the volume
will not be created for each new task). One also needs to enable the **auto-provisioning** option to create a volume
file on the running **EC2 instance** if it is missing.

Of course, the deployed application image needs to be properly configured for using the volume.

The file on the **EC2 instance** is located under **/var/lib/docker/volumes**. Naturally, if the instance dies, data
will be lost.

## Appendix

### Local docker compose .env files

messages/.env:

```
MESSAGES_PORT=8080
EUREKA_HOSTNAME=discovery
REDIS_HOSTNAME=redis
REDIS_PORT=6379
REDIS_USERNAME=messages
REDIS_PASSWORD=pass
```

gateway/.env

```
ALLOWED_ORIGINS=*
GATEWAY_PORT=8080
MESSAGES_SERVICE_ID=messages
EUREKA_HOSTNAME=discovery
```

### **EBS** for postgres on **EC2**

First of, you should start with the AMI with **docker**, **docker-compose** and **docker_boot.service** installed.

Then make sure to:

1) Create EBS volume in the same **AZ** in which your instance is located.
    - It needs to be in the same **AZ**, otherwise you need to create snapshot and move it to the desired **AZ**.
2) Attach it to some running instance under some directory.
    - I chose `/dev/xvdbb` to be consistent with root volume which is `/dev/xvda`.
    - Locate it with `lsblk`.
    - For me it showed it under the name `/dev/nvme1n1` and not `/dev/xvdbb`, but when I searched it with
      `ls /dev | grep xvd` it found it with the expected name `/dev/xvdbb`, but this is symlink to the `/dev/nvme1n1`.
3) Check if volume has any file system with `file -s /dev/nvme1n1`
    - if it does it will return something like:
      `/dev/nvme1n1: Linux rev 1.0 ext4 filesystem data, UUID=76e95df2-a381-4586-a2d1-1021946d3494 (extents) (64bit) (large files) (huge files)`
    - If it doesn't it will return something like: `/dev/nvme1n1: data`
4) If volume doesn't have any file system. Create it with `mkfs.ext4 /dev/nvme1n1`
5) Create directory for mount.
    - I created it under `/mnt/ebs/postgresql` but any is good.
    - `mkdir -p /mnt/ebs/postgresql`
6) Mount the drive with `mount /dev/nvme1n1 /mnt/ebs/postgresql`
7) Create **empty** directory for postgresql data, for example:
    - `mkdir /mnt/ebs/postgresql/data`
    - We cannot use the same directory from mounting, because ext4 automatically create `Lost+Found` directory and
      postgres expects empty directory.
8) If you reboot your instance, it won't mount the volume and you will need to do it again manually. To make it
   automatic locate the volume with `blkid`
    - For me it returned:
        - /dev/nvme0n1p1: LABEL="/" UUID="70d0467a-2f9b-4795-88e2-ccfd3849bd93" BLOCK_SIZE="4096" TYPE="xfs" PARTLABEL="
          Linux" PARTUUID="1de36676-cf9e-43ca-bbd1-b902e158566b"
        - /dev/nvme0n1p128: SEC_TYPE="msdos" UUID="EE7C-01A6" BLOCK_SIZE="512" TYPE="vfat" PARTLABEL="EFI System
          Partition" PARTUUID="a3e77220-94dc-4470-b4af-f1672d67006e"
        - /dev/nvme0n1p127: PARTLABEL="BIOS Boot Partition" PARTUUID="bce2db45-b333-4662-8c37-c7d84f0c9ce4"
        - /dev/nvme1n1: UUID="12e93b5f-f659-4f47-8c1f-0de741ca8eae" BLOCK_SIZE="4096" TYPE="ext4"
9) Take the UUID of the desired volume and add the following entry to the the `/etc/fstab` for example with:
    - run
      `echo UUID=12e93b5f-f659-4f47-8c1f-0de741ca8eae  /mnt/ebs/postgresql  ext4  defaults,nofail  0  2 >> /etc/fstab`
    - You can test if it works by:
        - running `umount /mnt/ebs/postgresql` (if you use `/dev/nvme1n1` it won't work)
        - and then `mount -a`

TLDR;

1) `mkfs.ext4 /dev/nvme1n1` If volume is new, else do only 3, 4, 5, 6
2) `mkdir -p /mnt/ebs/postgresql`
3) `blkid` and copy UUID of `/dev/nvme1n1`
4) `ID=<UUID>`
5) `echo UUID=$ID  /mnt/ebs/postgresql  ext4  defaults,nofail  0  2 >> /etc/fstab`
6) `mount -a`
7) `mkdir /mnt/ebs/postgresql/data`

I think these steps could be performed with a **bash** script, but I also think that after these steps are done, you can
create AMI and reuse it. As long as the volume does not change, you should be good.

#### How to use it with docker

Just use the `/mnt/ebs/postgresql/data` directory as bind mount.

The rest of the configuration is the same as in **EC2** deployment, in particular `user-data.txt` file in which you
create
`docker-compose.yaml` file that creates bind mount for mount directory.

example script: [postgresql user-data](./postgresql/user-data.txt)

Here for simplicity I use public subnet, to not have to play with S3.

### **EBS** for postgres on **ECS**

1) In task definition you want to select volume option - configure at deployment.
2) Remember to specify mount points, this is the same as in [Task definition storage](#Storage)
3) In service definition for the task, in volume section, configure the EBS volume, you will need to create and attach a
   role that can perform actions on your behalf with policy **AmazonECSInfrastructureRolePolicyForVolumes**. Basically
   you need to allow **ECS** service to create **EBS** volumes.

Task definition for redis with EBS: [redis task definition for EBS](./redis/redis-task-definition-EBS.json)

#### **EBS** infrastructure role for **ECS**

1) Create a new custom trust policy with these contents:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "AllowAccessToECSForInfrastructureManagement",
      "Effect": "Allow",
      "Principal": {
        "Service": "ecs.amazonaws.com"
      },
      "Action": "sts:AssumeRole"
    }
  ]
}
```

2) Attach **AmazonECSInfrastructureRolePolicyForVolumes** policy to it

See [link](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/ebs-volumes.html#ebs-volume-considerations)