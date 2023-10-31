with import <nixpkgs> {};
mkShell {
  buildInputs = [
    pkgs.buildpack
    pkgs.graalvm19-ce
  ];
}
