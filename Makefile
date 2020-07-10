
local:
	gradle bootjar
	docker build -t reactive .
	docker run -t reactive

re_deploy: build_jar build_docker deploy

create: build_jar build_docker get_cluster
	kubectl apply -f reactive.yaml

get_cluster:
	gcloud container clusters get-credentials super-brain --region us-central1 --project superbrain-282909

build_jar:
	gradle bootjar

build_docker:
	docker build -t reactive .
	docker tag reactive gcr.io/superbrain-282909/reactive
	docker push gcr.io/superbrain-282909/reactive

deploy: get_cluster
	kubectl patch deployment reactive -p "{\"spec\":{\"template\":{\"metadata\":{\"annotations\":{\"date\":\"`date +'%s'`\"}}}}}"
