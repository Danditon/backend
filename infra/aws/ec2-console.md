# Seoul EC2 콘솔 생성 명세

`expected-resources.json`은 콘솔 입력·관측값을 정규화한 고정 명세다. AWS API 요청이나 자동 프로비저닝 파일이 아니다. `python ops/tests/test_resource_spec.py [fixture.json]`으로 오프라인 검증한다. 기본 실행은 저장소 명세를 검사하며 실제 AWS 자원이나 권한을 검증하지 않는다.

## 계정과 기존 네트워크 확인

1. 승인된 기존 로그인 계정에서 리전을 **아시아 태평양(서울), ap-northeast-2**로 선택한다. 다른 계정으로 전환하지 않는다.
2. 실행 증거의 task-4 발견 기록에서 AMI, VPC, subnet, route table, Internet Gateway ID를 가져온다. 2026-11-04 발견값은 고정 AMI 이름 `ubuntu/images/hvm-ssd-gp3/ubuntu-noble-24.04-amd64-server-20261017`, 소유자 `099720109477`, x86_64, 가용 영역 `ap-northeast-2a`, 서브넷 `172.31.0.0/20`이다. 런타임 ID는 실행 증거에만 둔다.
3. VPC → 서브넷에서 발견한 **기존 기본 VPC의 기본 퍼블릭 서브넷**을 선택한다. 라우팅 테이블의 `0.0.0.0/0`이 같은 VPC에 연결된 Internet Gateway로 향하며 상태가 **Active**인지 다시 확인한다. ID와 관계를 실행 증거에 기록한다. 조건이 달라졌으면 중단한다. 새 VPC, 서브넷, 게이트웨이, 라우트를 만들지 않는다.
4. AMI 상세 페이지에서 발견한 ID의 이름·소유자·아키텍처·상태 `available`을 재확인한다. 기본 추천 Ubuntu 버전이나 다른 날짜의 이미지로 자동 대체하지 않는다. 이미지가 없어졌으면 승인된 명세를 갱신해야 한다.

## 공통 소유권 태그와 키 페어

모든 새 instance, root volume, primary network interface, security group, Elastic IP, imported key pair에 `Project=danditon`, `Environment=production`, `Owner=danditon`, `ManagedBy=manual-console` 태그를 적용한다. `Owner`는 운영 책임 팀이며 개인 연락처는 비공개 운영 증거에서 관리한다. 기존 VPC·서브넷·라우트에는 태그를 덮어쓰지 않는다.

운영자 단말에서 전용 Ed25519 키를 만들고 비밀키는 저장소 밖 접근 제한 경로에 보관한다. EC2 → Key pairs → Actions → **Import key pair**에서 이름 `danditon-operator-2026`, Public key contents에 `.pub` 한 줄만 입력하고 공통 태그를 추가한다. 비밀키를 업로드하거나 출력하지 않는다. 같은 이름이 있으면 소유권과 공개키 지문을 대조하고, 다른 키면 중단한다. 키 페어 지문은 SSH 서버 호스트키 지문과 다르다. 자동 배포용 키는 이후 호스트 설정 단계에서 별도로 등록한다.

## 보안 그룹

EC2 → Security Groups → Create security group에서 Group name `danditon-backend-prod`, Description `Danditon production HTTP and key-only SSH`, VPC는 위에서 확인한 기존 VPC를 선택한다. 아래 **두 개의 인바운드 규칙만** 추가한다.

| Type | Protocol | Port range | Source type | Source |
|---|---|---|---|---|
| SSH | TCP | 22 | Anywhere-IPv4 | `0.0.0.0/0` |
| HTTP | TCP | 80 | Anywhere-IPv4 | `0.0.0.0/0` |

IPv4 TCP22 전 세계 공개는 승인된 의도적인 설정이다. 호스트의 키 전용 SSH·fail2ban·별도 배포 계정 제한과 함께 사용한다. IPv6 SSH, MySQL 3306, 앱 8080, 포트 범위, 모든 프로토콜 인바운드는 추가하지 않는다. IPv6 주소를 할당하지 않는다. 아웃바운드는 기본 **All traffic / All / All / 0.0.0.0/0** 한 개를 유지한다. 공통 태그를 추가하고 생성한다. 기본 보안 그룹을 재사용하거나 추가 그룹을 함께 연결하지 않는다.

## 인스턴스 시작 화면

EC2 → Instances → Launch instances에서 다음 항목을 명시한다. [AWS 시작 절차](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/EC2_GetStarted.html)를 따르되 이 표의 승인값을 사용한다.

| 화면 / 필드 | 값 |
|---|---|
| Name and tags / Name | `danditon-backend-prod` |
| Add additional tags / Resource types | instance, volume, network interface에 공통 태그 |
| Application and OS Images / AMI | 증거의 발견 ID; 위 Canonical Ubuntu 24.04 이름·소유자 재확인 |
| Architecture | `64-bit (x86)` / `x86_64` |
| Instance type | `t3.small` (2 vCPU, 2 GiB) |
| Key pair (login) | `danditon-operator-2026` |
| Network settings / VPC, Subnet | 앞서 재확인한 기존 기본 VPC·ap-northeast-2a 서브넷 |
| Auto-assign public IP | **Disable**; 아래 EIP 연결 후 접속 |
| Firewall / Select existing security group | `danditon-backend-prod`만 선택 |
| IPv6 IPs | 할당 없음 / 0 |
| Configure storage / Root volume | 1개, `20` GiB, `gp3`, 3000 IOPS, 125 MiB/s |
| Advanced storage / Encrypted | **Encrypted** |
| KMS key | AWS 관리 `alias/aws/ebs`; 고객 관리 키 생성·선택 금지 |
| Delete on termination | **Yes**; 인스턴스 종료 전 검증된 외부 백업 필요 |
| Advanced details / IAM instance profile | **None**, 선택하지 않음 |
| Purchasing option | On-Demand; Spot 선택 안 함 |
| Tenancy | Shared / default |
| Credit specification | **Standard**, Unlimited 선택 안 함 |
| Metadata accessible | Enabled |
| Metadata version | **V2 only (token required)** |
| Metadata response hop limit | `1` |
| Allow tags in metadata | Disabled |
| User data | 빈 값 |
| Summary / Number of instances | `1` |

T3 기본 credit는 Unlimited이므로 Standard를 직접 확인한다. [RunInstances 파라미터](https://docs.aws.amazon.com/AWSEC2/latest/APIReference/API_RunInstances.html) 기준으로 선택한 인스턴스·AMI·볼륨·네트워크의 권한이 모두 필요하다. EBS 계정 기본 키가 고객 관리 키라면 그대로 진행하지 말고 `alias/aws/ebs`를 명시적으로 선택한다. 계정 전체 암호화 설정은 바꾸지 않는다. 요약 확인 후 승인된 생성 단계에서만 **Launch instance**를 누른다.

## 고정 공개 IPv4와 생성 후 확인

EC2 → Elastic IP addresses → Allocate Elastic IP address에서 Network border group **ap-northeast-2**, Public IPv4 address pool **Amazon's pool of IPv4 addresses**, 공통 태그를 선택하여 **1개**를 할당한다. Actions → Associate Elastic IP address에서 Resource type **Instance**, 방금 만든 인스턴스, 그 인스턴스의 primary private IPv4를 선택한다. 기존 연결 재할당은 허용하지 않는다. EIP·allocation·association·instance·volume·ENI·SG·key-pair ID 및 관계는 비공개 실행 증거에 기록한다. 임시 자동 공개 IPv4는 사용하지 않는다.

인스턴스 상태 검사, AMI 소유자, 보안 그룹 전체 규칙, volume 암호화/키/용량, credit Standard, IMDSv2 required, IAM profile 없음, 선택 VPC/subnet/route, EIP 연결, 모든 새 자원의 태그를 콘솔에서 확인한다. 누락 태그는 해당 새 자원에만 추가한다. 정규화한 관측 fixture를 검증기에 넣되 성공만으로 실제 콘솔 검증을 대체하지 않는다.

SSH 첫 접속 전에 Actions → Monitor and troubleshoot → Get system log에서 서버 호스트키 지문을 확보해 `known_hosts`를 고정한다. 지문을 얻지 못하면 접속을 중단한다. Ubuntu 초기 계정은 `ubuntu`이며 비밀키 읽기 권한은 운영자로 제한한다. [AWS 접속 전제 조건](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/connection-prereqs-general.html)을 참고한다. 콘솔 키 페어 지문이나 최초 네트워크 응답만으로 서버를 신뢰하지 않는다.

## 필요한 권한

로그인과 버튼 활성화는 실제 변경 권한의 증거가 아니다. 다음은 기존 인증 주체에 필요한 작업 목록이며 IAM 정책을 추가하는 절차가 아니다. 리소스·조건·SCP·permission boundary에 따른 실제 허용은 생성 단계에서 확인하고, 거부된 정확한 action을 기록하여 해당 단계를 중단한다.

| 단계 | 필요한 action (모두 `ec2:` 접두사) |
|---|---|
| 콘솔 조회 | DescribeRegions, DescribeAvailabilityZones, DescribeAccountAttributes, DescribeImages, DescribeInstanceTypes, DescribeVpcs, DescribeSubnets, DescribeRouteTables, DescribeInternetGateways, DescribeSecurityGroups, DescribeKeyPairs, DescribeInstances, DescribeInstanceStatus, DescribeVolumes, DescribeAddresses, DescribeNetworkInterfaces, DescribeInstanceCreditSpecifications |
| 생성 | ImportKeyPair, CreateSecurityGroup, AuthorizeSecurityGroupIngress, RunInstances, CreateTags, AllocateAddress, AssociateAddress |
| 서버 신뢰 | GetConsoleOutput |
| 운영·정정 | RebootInstances, StopInstances, StartInstances, ModifyInstanceAttribute, ModifyInstanceMetadataOptions, ModifyInstanceCreditSpecification, RevokeSecurityGroupIngress |
| 소유자 승인 후 종료 | TerminateInstances, DisassociateAddress, ReleaseAddress, DeleteSecurityGroup, DeleteKeyPair, DeleteVolume (남은 task 소유 볼륨만) |

RunInstances는 선택한 AMI·인스턴스·루트 볼륨·기본 네트워크 인터페이스·서브넷·그룹·키 페어 리소스에 대한 권한을 포함해야 한다. 별도 볼륨/ENI 생성이나 IAM profile/PassRole은 필요로 하지 않는다. 기본 egress를 유지하므로 AuthorizeSecurityGroupEgress/RevokeSecurityGroupEgress는 요청하지 않는다. UI별 추가 조회 권한이 필요하면 정확한 거부 action만 기록하며 광범위 관리자 권한을 가정하지 않는다. AWS 관리 EBS 암호화를 사용하고 고객 KMS 키/정책을 생성하지 않는다.

## 소유권과 종료 순서

이 문서의 완료는 실행 중인 자원 삭제를 뜻하지 않는다. 종료 승인 후 외부 백업 검증 → 배포 키·환경 secret 폐기 및 배포 workflow 비활성화 → task 인스턴스 종료 → EIP 연결 해제/반납 → task 소유 잔존 볼륨·SG·imported key pair 정리 → 잔존 EBS/EIP 비용 확인 순으로 진행한다. 기본 VPC·서브넷·라우트·게이트웨이는 유지한다. 중지된 인스턴스도 볼륨과 공개 IPv4 비용이 남는다. 패키지·백업 보존은 소유자와 별도 결정한다. 잘못 생성한 자원은 소유권을 확인한 해당 자원만 정리한다.
