require 'formula'

class SpringBoot < Formula
  homepage 'https://spring.io/projects/spring-boot'
  url 'https://repo.spring.io/snapshot/org/springframework/boot/spring-boot-cli/2.2.14.BUILD-SNAPSHOT/spring-boot-cli-2.2.14.BUILD-SNAPSHOT-bin.tar.gz'
  version '2.2.14.BUILD-SNAPSHOT'
  sha256 '9ff68cd7fea9f0733d06e8ca83b06a37fafd345b2a700c46c9ea2cd15ae65142'
  head 'https://github.com/spring-projects/spring-boot.git'

  if build.head?
    depends_on 'maven' => :build
  end

  def install
    if build.head?
      Dir.chdir('spring-boot-cli') { system 'mvn -U -DskipTests=true package' }
      root = 'spring-boot-cli/target/spring-boot-cli-*-bin/spring-*'
    else
      root = '.'
    end

    bin.install Dir["#{root}/bin/spring"]
    lib.install Dir["#{root}/lib/spring-boot-cli-*.jar"]
    bash_completion.install Dir["#{root}/shell-completion/bash/spring"]
    zsh_completion.install Dir["#{root}/shell-completion/zsh/_spring"]
  end
end
