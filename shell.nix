with import <nixpkgs> {};
mkShell {
  buildInputs = [
    pkgs.buildpack
    pkgs.graalvm17-ce
  ];
}
