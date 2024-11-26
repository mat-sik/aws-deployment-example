# AWS Deployment Example

## Deployment strategy For EC2

### 1) Install Docker and Docker-compose

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

### 2) Create docker_boot.service and copy it to /etc/system/systemd

This step allows docker-compose.yaml to be run on each EC2 startup, so that our app will always work.

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

```shell
sudo mv /home/ec2-user/docker_boot.service /etc/systemd/system
```

### 3) Create AMI from step 1) and 2)

To do so, you need to create EC2 instance, do the steps manually and after that create AMI from the instance. This is
equivalent in this case to taking the snapshot of the instance.

### 4) Create user-data script

The script should:

- fetch image from S3
- load image into docker
- create docker-compose.yaml
- fetch or create additional files for docker-compose.yaml
- enable and start docker_boot.service

example scripts:

- [redis user-data](./redis/user-data.txt)
- [discovery user-data](./discovery/user-data.txt)
- [messages user-data](./messages/user-data.txt)
- [gateway user-data](./gateway/user-data.txt)

### Enable and Start docker_boot.service

```shell
sudo systemctl enable docker_boot.service
sudo systemctl start docker_boot.service
```

### Notes

In case of my application, messages and gateway services need to call redis and discovery services.

I chose to select manually private ips for redis - 10.0.0.4 and discovery 10.0.0.5.

Later I reference these ips in user-data.

Another approach would be to use aws ec2 discover-instances, but this service requires either public network or
special interface endpoint(which is paid)

I also use IMSDv2 for fetching ip and hostname of the constructed ec2 instance. This is required for properly
configuring eureka server.

### EC2 templates

Instead of manually creating EC2 instances, the better approach is to create templates, there you can hard code
expected ips for redis and discovery services.

## VPC

### 1) Create custom VPC

If you want to use DNS to communicate between EC2 instances, enable DNS hostnames.

### 2) Create private subnet and public subnet

You need to create two route tables, one for public subnet and one for private subnet.

For internet connection in public subnet, you need to create internet gateway.

When creating subnets I assigned 10.0.0.0/17 for private and 10.0.128.0/17 for public.

To make subnet public. you should create record 0.0.0.0/0 that points to IGW.

#### Instance Connect Endpoint

To be able to connect to instances in private subnet, you need to create EC2 Instance Connect Endpoint.
You will need to assign it to the custom VPC, the private subnet and
security group that will be used to allow communication between given EC2 and ICE.
That is, for a given EC2 instance, you should create rule in its SG that allows inbound ssh from this SG.

#### S3 Gateway Endpoint

Also for the sake of my implementation, you should also allow instances to talk to S3. To make it work in private
subnets, you need to create S3 gateway endpoint and add create route for it in the route table. You should
also create the record for public subnet, because this is more efficient way of communication with S3 than public
internet.

To create the record, destination should be special prefix list from amazon for S3 and the target should be the S3
gateway endpoint.

In EC2 instances security groups you should allow outbound traffic for HTTPS for special prefix list for S3. Basically
the same on as in route table.

You also need to create IAM Role that has read permissions for S3, then you should assign this role to EC2 that needs
access to the S3

## S3

Technically, ECR would be a better choice here than S3, but you cannot call ECR from private subnets for free, you
would need interface endpoint.

Because of this I chose to use S3(ECR also uses S3 underneath).

To use S3 for this purpose, you need to build the image locally and save it with:

```shell
 docker save -o gateway:latest.tar gateway:latest
```

in user-data scripts you need to load the file into docker, you can do it with:

```shell
 docker load -i gateway:latest.tar
```

Remember to later remove the tar file

## Security groups

You should try to make the rules as strict as possible.

Generally, inbound rules should contain SG of the ICE for ssh and outbound rules should contain HTTPS for S3 prefix
list.

When you want to use eureka or redis, you should create outbound rules with custom TCP with appropriate ports.

### Eureka

For eureka server, you should enable custom TCP inbound rule for port on which server is hosted.

Services that depend on the eureka server, should allow outbound rules for the same port.

### Redis

For redis server, you also should use inbound rule with custom TCP with the port of the server.

## Frontend

To deploy frontend app, you can place it in S3 bucket which is configured for static file hosting.

You will also need to set up the cors, to do so, set .env variable of gateway service with hostname of S3

## Secrets

Instead of providing secrets via user-data script, a better approach would be to se SSM and fetch secrets with aws cli.

The drawback of this solution is that to use it in private subnet, you would need special interface endpoint(which is
paid)

## Describe instances

If you could use public subnet only or use ec2 endpoint interface, you could use aws cli ec2 describe-instances command
to fetch hostnames and ips of instances by tag, this simplify connection configuration between services.

## Instance Connect Endpoint

To set it up you need to create Instance Connect Endpoint, assign it to your custom VPC and to one of the subnets.
I chose private subnet in AZ A.

Then you need to create Security group for the ICE, there you need to create inbound and outbound rules for each
instance security group you want to connect to, the port should be SSH

## Subnet per AZ for private public net

If VPC is 10.0.0.0/16 32k

```
aws-deployment-example-private-subnet-a 10.0.0.0/18 16k
aws-deployment-example-private-subnet-b 10.0.64.0/19 8k
aws-deployment-example-private-subnet-c 10.0.96.0/19 8k

ws-deployment-example-public-subnet-a 10.0.128.0/18 16k
aws-deployment-example-public-subnet-b 10.0.192.0/19 8k
aws-deployment-example-public-subnet-c 10.0.224.0/19 8k
```

## Local docker compose .env files

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

## Route 53

To not use hardcoded ipv4 addresses for Discovery service and redis, you can create A records for these ips in Route 53.

To do so, create private hosted zone for you VPC, enable VPC hostname in VPC settings and create two A records.

For example, when you create private hosted zone for your VPC(and you enabled hostname resolution and DNS hostnames)

Create these two records:

```
Record name | type | routing policy | target | ttl
discovery.messages.com A Simple 10.0.0.5 300
redis.messages.com A  Simple 10.0.0.4 300
```

YOu need to ensure that the discovery and redis have these ips

## Cloud map

You can also use Cloud Map instead of Route 53 to create DNS hostnames. In my use case there is no difference.

You first need to create a namespace. Then specify whether you only want to do API calls with AWS discover instances or
if you also want to use DNS.

I think you are able to do both of these things without cloud map, You can use hostnames from Route 53 private hosted
zone
and use aws ec2 discover instances by TAGs.

to use cloud map, after you've created namespace for API calls and private DNS,
you need to create a service for your target instance. There you can define options for DNS and health checks.
Then for the service you can register instance, by providing it IP and port.

For me it looks like high level unnecesary abstraction over Route 53

The dns name for each service is:

```
service_name.namespace_name -> ipv4 for the service if A record used in service definition
```

## Deployment strategy For ECS

Because I want to do things cheaply, we need to run our tasks in public subnet, so that we can access ECR and other
aws services without interface endpoint(for free)

### Public Cluster

I use cluster with EC2 ASG.

#### Network

The ECS cluster should be placed in the public net to allow free access to AWS services. SG for cluster instances should
allow
the same traffic that is needed for the tasks. In my case http for messages service and tcp with the port of the redis
for redis.

When using Docker Bridge, the tasks don't have their own SG, they share them with the ECS EC2 instances.

### Redis Task definition

- [redis task definition json](./redis/redis-task-definition.json)

I want to deploy redis with users.acl, so that the redis client needs to use username and password. I also want to use
custom redis.conf. Because of these two things we should somehow mount redis.conf and users.acl into docker container.
This is hard to do in ECS task. To fulfil this requirement, the proper solution is to create custom docker image with
those two files already copied with the use of Dockerfile. To change password or some setting, one would need to create
new
version of the image.

#### Resources

At most I could specify 2 vCPU and 0.905 GB for t3.micro (free-tier). The rest of the 1GB is probably needed by ECS
agent
container which runs on each ECS instance.

#### Image

Image can be loaded from public repositories or public or private ECR repo.

#### Network

To make the task discoverable from public net, we need to use host type of bridge. awsvpc does not work, because it does
not give public ips to the tasks, one would need to use NAT gateway, which costs money.

Docker bridge requires specifying port on the host EC2 instance and docker container, typical stuff for docker.

In theory network type Host could also be used, in practice we would get the same deployment type as in EC2 deployment.

#### Storage

To use storage to persist the data, one can use docker volume with driver local and Scope shared. One also needs to
enable use auto-provisioning option, to create volume file on running EC2 instance if it is missing.

Of course you need to mount the file in the docker container to the proper directory that is required by the image.

The file on the EC2 instance is located under /var/lib/docker/volumes. Naturally if the instance dies, data will be
lost.

